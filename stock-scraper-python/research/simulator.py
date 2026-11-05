"""組合模擬器 v2：核心（0050）＋衛星（策略選股）的一筆資金，逐日模擬。

與 scraper.portfolio.simulate_portfolio 的差別（舊版的條件可完整重現，見 legacy_plan 與 research.validate）：
- 核心 / 衛星：本金的 core_weight 在第一天之後的開盤買進 0050 持有（可每年初再平衡），其餘為衛星資金
- 部位大小：equal 每筆「衛星資金 ÷ 檔數」（舊版）；risk 每筆最多虧衛星資金的 risk_per_trade，
  依停損距離算股數，並受單檔上限 max_weight 限制
- 出場：ATR 倍數移動停利、百分比移動停利、跌破季線、持有 N 日，可加「漲到 take_profit 先賣一部分」
- 大盤濾網：0050 收盤在 N 日均線之下時，no_new 不開新倉、exit 另外把衛星全部賣出
- 操作頻率：daily 每天收盤後判斷；weekly 只在每週最後一個交易日收盤後判斷（訊號為當週出現過的）
- 同一天的候選多於空位時：random 依亂數種子（舊版）、strength 依 120 日漲幅由強到弱
- 成本與成交：與 scraper.backtest 相同（隔日開盤進場、開盤近漲停買不到、手續費與證交稅、除息日股利入帳）；
  whole_shares 為 True 時只買整數股（零股），否則依金額買（舊版）

0050 買進持有的基準就是 core_weight = 1 的計畫，與策略走同一段程式。
"""

import math
import random
from dataclasses import dataclass, field, replace
from datetime import date

from scraper.backtest import BUY_FEE, EXIT_RULES, ExitRule, opens_at_limit_up
from scraper.portfolio import Stock, sell_cost

ETF = "0050.TW"
STRENGTH_DAYS = 120


@dataclass(frozen=True)
class ExitSpec:
    """出場方式。kind：atr（最高收盤 − 倍數 × ATR）、pct（最高收盤 × (1 − pct)）、ma60（跌破季線）、hold（持有 N 日）。
    atr / pct / ma60 在收盤跌破後的下一個交易日開盤賣出；hold 與 max_days 到期於收盤賣出。"""

    kind: str
    atr_multiple: float = 3.0
    pct: float = 0.15
    hold_days: int | None = None
    max_days: int | None = None
    take_profit: float | None = None  # 收盤漲幅達到此比例時，下一個開盤先賣 take_fraction
    take_fraction: float = 0.5


@dataclass(frozen=True)
class Plan:
    strategies: tuple[str, ...]  # 任一策略出現訊號即為候選
    exit: ExitSpec
    capital: float = 1_000_000.0
    core_weight: float = 0.0
    rebalance: str = "none"  # none / yearly（每年第一個交易日開盤把核心調回 core_weight）
    slots: int = 10
    sizing: str = "equal"  # equal / risk
    risk_per_trade: float = 0.01
    max_weight: float = 0.2  # 單檔上限（占衛星資金）；sizing = risk 時使用
    market_filter: str = "none"  # none / no_new / exit
    filter_ma: int = 60
    frequency: str = "daily"  # daily / weekly
    rank: str = "random"  # random / strength
    seed: int = 0
    whole_shares: bool = True


def legacy_plan(strategy: str, code: str, seed: int, slots: int = 10) -> Plan:
    """與 scraper.portfolio.simulate_portfolio 相同的條件（起始 1 元、依金額買、等額、沒有濾網）。"""
    return Plan((strategy,), exit_spec(EXIT_RULES[code]), capital=1.0, slots=slots, seed=seed, whole_shares=False)


def exit_spec(rule: ExitRule) -> ExitSpec:
    if rule.hold_days:
        return ExitSpec("hold", hold_days=rule.hold_days)
    if rule.below_ma60:
        return ExitSpec("ma60", max_days=rule.max_days)
    return ExitSpec("atr", atr_multiple=rule.trailing_atr, max_days=rule.max_days)


@dataclass
class Position:
    symbol: str
    shares: float
    entry_index: int
    entry_date: date
    entry_price: float
    highest: float = -math.inf
    pending: str | None = None  # 下一個開盤要執行：exit 全部賣出 / partial 先賣一部分
    pending_reason: str | None = None
    partial_done: bool = False


@dataclass
class Result:
    dates: list[date]
    equity: list[float]
    invested: list[float]  # 衛星持股市值 ÷ 總資金
    trades: int = 0
    skipped: int = 0
    exits: dict = field(default_factory=dict)  # 出場原因 → 次數
    log: list = field(default_factory=list)  # (日期, 股票, buy / sell, 股數, 價格, 原因)：檢查與報告用


def market_filter_days(etf: Stock, ma_days: int) -> dict[date, bool]:
    """每個交易日收盤時 0050 是否在 N 日均線之上（均線資料不足的日子視為在上方，不限制）。"""
    closes = etf.bars.close
    result, total = {}, 0.0
    for i, day in enumerate(etf.bars.dates):
        total += closes[i]
        if i >= ma_days:
            total -= closes[i - ma_days]
        result[day] = i + 1 < ma_days or closes[i] >= total / ma_days
    return result


def decision_days(calendar: list[date], frequency: str) -> set[date]:
    """可以做判斷的收盤：每天，或每週最後一個交易日。"""
    if frequency == "daily":
        return set(calendar)
    days = set()
    for day, following in zip(calendar, calendar[1:] + [None]):
        if following is None or following.isocalendar()[:2] != day.isocalendar()[:2]:
            days.add(day)
    return days


def strength(stock: Stock, i: int) -> float:
    if i < STRENGTH_DAYS:
        return -math.inf
    return stock.bars.close[i] / stock.bars.close[i - STRENGTH_DAYS] - 1


def entry_candidates(stocks: dict[str, Stock], plan: Plan, decide: set[date]) -> dict[date, list[tuple[str, int]]]:
    """進場日 → [(股票, 訊號日 index)]。daily：訊號日的下一個交易日；weekly：當週出現過訊號、下週第一個交易日。"""
    candidates: dict[date, list] = {}
    for symbol, stock in stocks.items():
        if symbol == ETF and plan.core_weight > 0:
            continue
        dates, last = stock.bars.dates, len(stock.bars.dates) - 1
        signal_indexes = set().union(*(stock.entries.get(s, set()) for s in plan.strategies))
        if plan.frequency == "daily":
            for i in signal_indexes:
                if stock.bars.start <= i < last:
                    candidates.setdefault(dates[i + 1], []).append((symbol, i))
            continue
        # 每週：以當週最後一個交易日（判斷日）為準，判斷日之後這檔股票的第一個交易日進場
        by_week: dict = {}
        for i in signal_indexes:
            if stock.bars.start <= i:
                by_week.setdefault(dates[i].isocalendar()[:2], []).append(i)
        for indexes in by_week.values():
            i = max(indexes)
            decided = next((j for j in range(i, last + 1) if dates[j] in decide), None)
            if decided is not None and decided < last:
                candidates.setdefault(dates[decided + 1], []).append((symbol, decided))
    return candidates


def simulate(stocks: dict[str, Stock], plan: Plan) -> Result:
    """stocks 需包含 0050（核心或大盤濾網使用時）。回傳每個交易日收盤後的總資金。"""
    rng = random.Random(plan.seed)
    rule = plan.exit
    universe = {s: st for s, st in stocks.items() if not (s == ETF and plan.core_weight > 0)}
    # 交易日：衛星股票的交易日；有核心時加上 0050 的交易日（全部是核心時只有 0050）
    traded = [] if plan.core_weight >= 1 else list(universe.values())
    if plan.core_weight > 0:
        traded.append(stocks[ETF])
    calendar = sorted({d for s in traded for d in s.bars.dates[s.bars.start:]})
    decide = decision_days(calendar, plan.frequency)
    candidates = entry_candidates(universe, plan, decide) if plan.core_weight < 1 else {}
    above_ma = market_filter_days(stocks[ETF], plan.filter_ma) if plan.market_filter != "none" else {}
    shares_of = (lambda amount, price: math.floor(amount / price)) if plan.whole_shares else (lambda amount, price: amount / price)

    cash = plan.capital
    equity = satellite_equity = plan.capital
    core_shares, core_cash = 0.0, 0.0  # 核心的股數與已入帳的股利（股利不再投入，與買進持有的基準相同）
    core_bought = False
    core_date = None  # 核心買進日：當天除息領不到
    positions: dict[str, Position] = {}
    last_close: dict[str, float] = {}
    result = Result([], [], [])
    filter_ok = True  # 前一個判斷日收盤時大盤是否在均線之上

    def sell(day: date, p: Position, fraction: float, price: float, reason: str) -> None:
        nonlocal cash
        amount = p.shares if fraction >= 1 else shares_of(p.shares * fraction, 1.0)
        cash += amount * price * (1 - sell_cost(p.symbol))
        p.shares -= amount
        result.exits[reason] = result.exits.get(reason, 0) + 1
        result.log.append((day, p.symbol, "sell", amount, price, reason))

    etf = stocks.get(ETF)
    for n, day in enumerate(calendar):
        # 0. 核心：第二天開盤買進；每年第一個交易日開盤再平衡
        if plan.core_weight > 0 and etf is not None and day in etf.index_of:
            k = etf.index_of[day]
            price = etf.bars.open[k]
            if not core_bought and n >= 1:
                amount = plan.capital * plan.core_weight
                core_shares = shares_of(amount, price * (1 + BUY_FEE))
                cash -= core_shares * price * (1 + BUY_FEE)
                core_bought, core_date = True, day
                result.log.append((day, ETF, "buy", core_shares, price, "core"))
            elif core_bought and plan.rebalance == "yearly" and calendar[n - 1].year != day.year:
                target = equity * plan.core_weight
                current = core_shares * price
                if current > target:
                    sold = shares_of(current - target, price)
                    core_shares -= sold
                    cash += sold * price * (1 - sell_cost(ETF))
                    result.log.append((day, ETF, "sell", sold, price, "rebalance"))
                else:
                    bought = shares_of(min(target - current, cash), price * (1 + BUY_FEE))
                    core_shares += bought
                    cash -= bought * price * (1 + BUY_FEE)
                    result.log.append((day, ETF, "buy", bought, price, "rebalance"))

        # 1. 開盤賣出（前一個判斷日收盤決定的）：同一檔當天賣出後不再當天買回
        sold_today = set()
        for symbol in [s for s, p in positions.items() if p.pending]:
            stock = universe[symbol]
            k = stock.index_of.get(day)
            if k is None:
                continue  # 停牌：等這檔下一個有行情的交易日
            p = positions[symbol]
            if p.pending == "partial":
                sell(day, p, rule.take_fraction, stock.bars.open[k], "partial")
                p.pending, p.partial_done = None, True
                continue
            sell(day, p, 1.0, stock.bars.open[k], p.pending_reason)
            del positions[symbol]
            sold_today.add(symbol)

        # 2. 開盤進場
        todays = sorted(candidates.get(day, []))
        if plan.rank == "strength":
            todays.sort(key=lambda c: (-strength(universe[c[0]], c[1]), c[0]))
        else:
            rng.shuffle(todays)
        blocked = plan.market_filter != "none" and not filter_ok
        budget = satellite_equity / plan.slots
        for symbol, i in todays:
            if symbol in positions or symbol in sold_today:
                continue
            if blocked:
                result.skipped += 1
                continue
            if len(positions) >= plan.slots or cash <= 1e-12:
                result.skipped += 1
                continue
            stock = universe[symbol]
            entry = i + 1
            if opens_at_limit_up(stock.bars, entry):
                continue  # 開盤近漲停，買不到
            price = stock.bars.open[entry]
            if plan.sizing == "risk":
                stop = stop_distance(stock, i, rule)
                if stop is None or stop <= 0:
                    continue
                amount = min(satellite_equity * plan.risk_per_trade / stop * price, satellite_equity * plan.max_weight, cash)
            else:
                amount = min(budget, cash)
            shares = shares_of(amount, price * (1 + BUY_FEE))
            if shares <= 0:
                continue
            cash -= shares * price * (1 + BUY_FEE)
            positions[symbol] = Position(symbol, shares, entry, stock.bars.dates[entry], price)
            result.trades += 1
            result.log.append((day, symbol, "buy", shares, price, None))

        # 3. 除息日現金股利入帳（進場當天除息則領不到）
        for symbol, p in positions.items():
            amount = universe[symbol].bars.dividends.get(day)
            if amount and day > p.entry_date:
                cash += p.shares * amount
        if core_shares and etf is not None and day > core_date:
            amount = etf.bars.dividends.get(day)
            if amount:
                core_cash += core_shares * amount

        # 4. 收盤：到期的收盤賣出；其他出場條件成立時排到下一個開盤
        is_decision = day in decide
        if plan.market_filter != "none" and is_decision:
            filter_ok = above_ma.get(day, filter_ok)
        for symbol in list(positions):
            stock = universe[symbol]
            k = stock.index_of.get(day)
            if k is None:
                continue
            p = positions[symbol]
            close = stock.bars.close[k]
            p.highest = max(p.highest, close)
            held = k - p.entry_index + 1
            if (rule.kind == "hold" and held == rule.hold_days) or (rule.max_days and held >= rule.max_days):
                sell(day, p, 1.0, close, "hold" if rule.kind == "hold" else "max_hold")
                del positions[symbol]
                continue
            if not is_decision or p.pending == "exit":
                continue
            reason = exit_reason(stock, k, p, rule)
            if reason is None and plan.market_filter == "exit" and not filter_ok:
                reason = "market"
            if reason is not None:
                p.pending, p.pending_reason = "exit", reason
            elif rule.take_profit and not p.partial_done and p.pending is None and close >= p.entry_price * (1 + rule.take_profit):
                p.pending = "partial"

        # 5. 收盤計值：當天沒有資料的股票沿用前一個收盤價
        for symbol, stock in universe.items():
            k = stock.index_of.get(day)
            if k is not None:
                last_close[symbol] = stock.bars.close[k]
        if etf is not None and day in etf.index_of:
            last_close[ETF] = etf.bars.close[etf.index_of[day]]
        holdings = sum(p.shares * last_close[s] for s, p in positions.items())
        core_value = core_shares * last_close.get(ETF, 0.0) + core_cash
        equity = cash + holdings + core_value
        # 衛星資金：核心尚未買進前，先扣除要留給核心的部分
        satellite_equity = cash + holdings - (0.0 if core_bought else plan.capital * plan.core_weight)
        result.dates.append(day)
        result.equity.append(equity)
        result.invested.append(holdings / equity if equity > 0 else 0.0)

    # 最新一天仍持有：以收盤扣除賣出成本計值
    if result.equity and (positions or core_shares):
        result.equity[-1] = (
            cash + core_cash
            + sum(p.shares * last_close[s] * (1 - sell_cost(s)) for s, p in positions.items())
            + core_shares * last_close.get(ETF, 0.0) * (1 - sell_cost(ETF))
        )
    return result


def exit_reason(stock: Stock, k: int, p: Position, rule: ExitSpec) -> str | None:
    """收盤時的出場條件（與 scraper.backtest.simulate 相同）；成立時下一個交易日開盤賣出。"""
    bars = stock.bars
    close = bars.close[k]
    if rule.kind == "atr":
        atr = bars.atr[k]
        if not math.isnan(atr) and close < p.highest - rule.atr_multiple * atr:
            return "trailing"
    elif rule.kind == "pct":
        if close < p.highest * (1 - rule.pct):
            return "trailing"
    elif rule.kind == "ma60":
        if not math.isnan(bars.ma60[k]) and close < bars.ma60[k]:
            return "ma_break"
    return None


def stop_distance(stock: Stock, i: int, rule: ExitSpec) -> float | None:
    """訊號日收盤時，依出場方式估計的停損距離（每股元），用來決定部位大小。"""
    bars = stock.bars
    close = bars.close[i]
    if rule.kind == "atr":
        return None if math.isnan(bars.atr[i]) else rule.atr_multiple * bars.atr[i]
    if rule.kind == "pct":
        return close * rule.pct
    if rule.kind == "ma60":
        if math.isnan(bars.ma60[i]):
            return None
        # 已在季線附近時，以 1 個 ATR 為最小距離，避免算出過大的部位
        floor = 0.0 if math.isnan(bars.atr[i]) else bars.atr[i]
        return max(close - bars.ma60[i], floor)
    return None if math.isnan(bars.atr[i]) else 2 * bars.atr[i]  # 持有 N 日：以 2 ATR 估計


def benchmark_plan(capital: float = 1_000_000.0, whole_shares: bool = True) -> Plan:
    """0050 買進持有：本金全部在第二天開盤買進 0050。"""
    return Plan((), ExitSpec("hold", hold_days=1), capital=capital, core_weight=1.0, whole_shares=whole_shares)


__all__ = ["ExitSpec", "Plan", "Result", "benchmark_plan", "exit_spec", "legacy_plan", "replace", "simulate"]
