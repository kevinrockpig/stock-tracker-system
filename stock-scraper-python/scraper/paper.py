"""交易計畫與往後實測（模擬帳戶）：觀察中策略從啟用日起，每天收盤後依計畫模擬交易並保存紀錄。

執行：python -m scraper.paper（每日 scraper.main 在投資組合回測之後也會自動執行）

規則與投資組合回測（portfolio.py）完全相同：最多同時持有 SLOTS 檔、每次進場分配前一日總資金的 1/SLOTS、
訊號隔日開盤進場、開盤近漲停略過、同一檔當天賣出不當天買回、出場規則與成本、股利在除息日入帳。
差別在於這裡是「往後」逐日執行：每個交易日只處理一次，處理過的日子不會重算，因此紀錄不會事後修改。

每個交易日 D（依序處理上次處理日之後的每個交易日；電腦關機漏掉的日子下次補上，每天只用當天以前的資料）：
  1. 開盤賣出：前一天產生的賣出計畫（跌破移動停利線、跌破季線）；當天沒有該股資料（停牌、缺資料）則留到下一天
  2. 開盤買進：前一天產生的買進計畫，依計畫中的隨機順序；空位不足、開盤近漲停則略過並記錄原因；
     當天沒有該股資料則留到它下一個有資料的交易日（與投資組合回測相同：訊號後該股的下一根 K 線進場）
  3. 除息日現金股利入帳
  4. 收盤賣出：持有期滿、達最長持有天數
  5. 收盤計值並記錄當天資金
  6. 檢查出場條件：收盤跌破停利線（進場後最高收盤 − 倍數 × 當天 ATR）或季線 → 產生「下一個交易日開盤賣出」計畫
  7. 當天出現訊號、未持有的股票 → 產生「下一個交易日開盤買進」計畫（同一天多檔時以隨機順序排定優先順序）
"""

import argparse
import logging
import random
import sys
from dataclasses import dataclass, field
from datetime import date

from sqlalchemy import create_engine

from scraper.backtest import (
    BUY_FEE,
    LIMIT_UP_SKIP,
    TRAILING_ATR_MULTIPLE,
    Bars,
    opens_at_limit_up,
    rules_for,
)
from scraper.config import WATCHLIST, ConfigError, build_database_url
from scraper.db import init_schema, load_paper_state, save_paper_day
from scraper.portfolio import SLOTS, Stock, load_stocks, sell_cost
from scraper.strategies import STRATEGIES

logger = logging.getLogger(__name__)

# 觀察中策略：（策略, 出場規則）。依投資組合回測選出的「最有希望的候選」，從啟用日起往後實測
PAPER_STRATEGIES = [("high_120", "trailing_nocap")]
INITIAL_CAPITAL = 1_000_000.0


@dataclass
class Position:
    symbol: str
    signal_date: date
    entry_date: date
    entry_price: float
    shares: float
    cost: float  # 買進金額（含手續費）
    highest_close: float | None = None
    held_days: int = 0
    dividends: float = 0.0  # 已入帳的現金股利（元）
    exit_date: date | None = None
    exit_price: float | None = None
    exit_reason: str | None = None
    id: int | None = None  # 資料庫中的編號（新部位為 None）

    @property
    def net_return(self) -> float | None:
        if self.exit_price is None:
            return None
        proceeds = self.shares * self.exit_price * (1 - sell_cost(self.symbol)) + self.dividends
        return proceeds / self.cost - 1


@dataclass
class Plan:
    plan_date: date
    symbol: str
    action: str  # buy / sell
    reason: str  # 訊號代碼（買進）或出場原因（賣出）
    priority: int = 0  # 買進的執行順序（同一天隨機排定）
    close_price: float | None = None
    reference_price: float | None = None  # 下一個交易日的參考價（不含尚未得知的除息）
    skip_above: float | None = None  # 開盤高於此價視為接近漲停而略過
    trailing_line: float | None = None
    atr_stop: float | None = None  # 買進時的 ATR 停損參考（收盤 − 倍數 × ATR）
    status: str = "pending"  # pending / executed / skipped_no_slot / skipped_limit_up / skipped_held / skipped_no_data
    executed_date: date | None = None
    id: int | None = None


@dataclass
class Account:
    strategy: str
    exit_rule: str
    slots: int
    initial_capital: float
    inception_date: date
    last_processed: date
    cash: float
    positions: list[Position] = field(default_factory=list)  # 持有中
    pending: list[Plan] = field(default_factory=list)  # 尚未執行的計畫
    equity: float = 0.0  # 最近一次收盤的總資金
    id: int | None = None


@dataclass
class DayResult:
    """一個交易日處理完的結果（寫入資料庫用）。"""
    day: date
    cash: float
    holdings: float
    equity: float
    opened: list[Position]
    closed: list[Position]
    updated: list[Position]
    resolved_plans: list[Plan]
    new_plans: list[Plan]


def _bar(stock: Stock, day: date) -> int | None:
    return stock.index_of.get(day)


def step_day(account: Account, day: date, stocks: dict[str, Stock], last_close: dict[str, float]) -> DayResult:
    """處理一個交易日（見模組說明），直接更新 account。last_close：各股票最近的收盤價（停牌時沿用）。"""
    rule = rules_for(account.strategy)[account.exit_rule]
    opened, closed, resolved = [], [], []
    held = {p.symbol: p for p in account.positions}

    # 1. 開盤賣出
    sold_today = set()
    for plan in [p for p in account.pending if p.action == "sell"]:
        position = held.get(plan.symbol)
        i = _bar(stocks[plan.symbol], day) if plan.symbol in stocks else None
        if position is None:
            plan.status = "skipped_no_data"  # 理論上不會發生：部位已不存在
        elif i is None:
            continue  # 停牌或缺資料：留到下一個有資料的交易日
        else:
            position.exit_date, position.exit_price, position.exit_reason = day, stocks[plan.symbol].bars.open[i], plan.reason
            account.cash += position.shares * position.exit_price * (1 - sell_cost(plan.symbol))
            account.positions.remove(position)
            del held[plan.symbol]
            closed.append(position)
            sold_today.add(plan.symbol)
            plan.status = "executed"
        plan.executed_date = day
        resolved.append(plan)

    # 2. 開盤買進（依計畫的優先順序）
    budget = account.equity / account.slots
    for plan in sorted([p for p in account.pending if p.action == "buy"], key=lambda p: p.priority):
        stock = stocks.get(plan.symbol)
        i = _bar(stock, day) if stock else None
        if i is None:
            continue  # 停牌或缺資料：留到該股下一個有資料的交易日
        if plan.symbol in held or plan.symbol in sold_today:
            plan.status = "skipped_held"
        elif i == 0:
            plan.status = "skipped_no_data"
        elif len(account.positions) >= account.slots or account.cash <= 1e-9:
            plan.status = "skipped_no_slot"
        elif opens_at_limit_up(stock.bars, i):
            plan.status = "skipped_limit_up"
        else:
            amount = min(budget, account.cash)
            price = stock.bars.open[i]
            position = Position(plan.symbol, plan.plan_date, day, price, amount / (price * (1 + BUY_FEE)), amount)
            account.cash -= amount
            account.positions.append(position)
            held[plan.symbol] = position
            opened.append(position)
            plan.status = "executed"
        plan.executed_date = day
        resolved.append(plan)
    account.pending = [p for p in account.pending if p.status == "pending"]

    # 3. 除息日現金股利（進場當天除息則領不到）
    for position in account.positions:
        amount = stocks[position.symbol].bars.dividends.get(day) if position.symbol in stocks else None
        if amount and day > position.entry_date:
            position.dividends += position.shares * amount
            account.cash += position.shares * amount

    # 4. 收盤賣出（持有期滿、達最長持有天數）；同時更新持有天數與最高收盤
    for position in list(account.positions):
        stock = stocks.get(position.symbol)
        i = _bar(stock, day) if stock else None
        if i is None:
            continue
        position.held_days += 1
        close = stock.bars.close[i]
        position.highest_close = close if position.highest_close is None else max(position.highest_close, close)
        limit = rule.hold_days or rule.max_days
        if limit and position.held_days >= limit:
            position.exit_date, position.exit_price = day, close
            position.exit_reason = "hold" if rule.hold_days else "max_hold"
            account.cash += position.shares * close * (1 - sell_cost(position.symbol))
            account.positions.remove(position)
            del held[position.symbol]  # 收盤賣出後，當天的新訊號可再產生買進計畫（與個別回測一致）
            closed.append(position)

    # 5. 收盤計值
    for symbol, stock in stocks.items():
        i = _bar(stock, day)
        if i is not None:
            last_close[symbol] = stock.bars.close[i]
    holdings = sum(p.shares * last_close[p.symbol] for p in account.positions)
    account.equity = account.cash + holdings

    # 6. 出場條件：收盤跌破停利線或季線 → 下一個交易日開盤賣出
    new_plans = []
    for position in account.positions:
        stock = stocks[position.symbol]
        i = _bar(stock, day)
        if i is None:
            continue
        close, atr, ma60 = stock.bars.close[i], stock.bars.atr[i], stock.bars.ma60[i]
        line = position.highest_close - rule.trailing_atr * atr if rule.trailing_atr and atr == atr else None
        reason = None
        if line is not None and close < line:
            reason = "trailing"
        if rule.below_ma60 and ma60 == ma60 and close < ma60:
            reason = "ma_break"
        if reason:
            new_plans.append(Plan(day, position.symbol, "sell", reason, close_price=close, trailing_line=line))

    # 7. 新訊號 → 下一個交易日開盤買進（未持有的股票；同一天多檔時隨機排定順序）
    candidates = sorted(
        symbol for symbol, stock in stocks.items()
        if (i := _bar(stock, day)) is not None and i >= stock.bars.start
        and i in stock.entries.get(account.strategy, ()) and symbol not in held
    )
    random.Random(f"{day}/{account.strategy}/{account.exit_rule}").shuffle(candidates)
    for priority, symbol in enumerate(candidates):
        bars: Bars = stocks[symbol].bars
        i = _bar(stocks[symbol], day)
        close, atr = bars.close[i], bars.atr[i]
        new_plans.append(Plan(
            day, symbol, "buy", account.strategy, priority=priority, close_price=close,
            reference_price=close, skip_above=close * (1 + LIMIT_UP_SKIP),
            atr_stop=close - TRAILING_ATR_MULTIPLE * atr if atr == atr else None,
        ))
    account.pending.extend(new_plans)
    account.last_processed = day
    return DayResult(day, account.cash, holdings, account.equity, opened, closed, list(account.positions),
                     resolved, new_plans)


def trading_days_after(stocks: dict[str, Stock], after: date) -> list[date]:
    return sorted({d for s in stocks.values() for d in s.bars.dates if d > after})


def run_paper(engine, symbols) -> list[tuple[Account, list[DayResult]]]:
    """處理每個觀察中策略從上次處理日之後的交易日；第一次執行時以最新交易日為啟用日。"""
    with engine.connect() as conn:
        stocks = load_stocks(conn, symbols)
    if not stocks:
        return []
    latest = max(d for s in stocks.values() for d in s.bars.dates)
    results = []
    for strategy, exit_rule in PAPER_STRATEGIES:
        with engine.connect() as conn:
            account = load_paper_state(conn, strategy, exit_rule)
            if account is None:
                # 啟用：從最新交易日起實測，這天只產生計畫
                account = Account(strategy, exit_rule, SLOTS, INITIAL_CAPITAL, latest, latest, INITIAL_CAPITAL,
                                  equity=INITIAL_CAPITAL)
                days = [latest]
            else:
                days = trading_days_after(stocks, account.last_processed)
            # 持有中但已移出觀察名單的股票，仍需載入行情
            missing = [p.symbol for p in account.positions if p.symbol not in stocks]
            if missing:
                stocks.update(load_stocks(conn, missing))
        last_close = {}
        for symbol, stock in stocks.items():
            known = [i for d, i in stock.index_of.items() if d <= account.last_processed]
            if known:
                last_close[symbol] = stock.bars.close[max(known)]
        day_results = []
        for day in days:  # 啟用日沒有待執行的計畫，只產生新計畫
            result = step_day(account, day, stocks, last_close)
            with engine.begin() as conn:
                save_paper_day(conn, account, result)
            day_results.append(result)
        results.append((account, day_results))
    return results


def log_paper(results) -> None:
    for account, days in results:
        name = f"{STRATEGIES[account.strategy]}＋{rules_for(account.strategy)[account.exit_rule].label}"
        if not days:
            logger.info("===== 交易計畫：%s 已處理到 %s，沒有新的交易日 =====", name, account.last_processed)
            continue
        for result in days:
            logger.info(
                "===== 交易計畫：%s %s（啟用 %s）總資金 %s（%+.2f%%）現金 %s 持股 %d 檔 =====",
                name, result.day, account.inception_date, f"{result.equity:,.0f}",
                (result.equity / account.initial_capital - 1) * 100, f"{result.cash:,.0f}", len(result.updated),
            )
            for p in result.closed:
                logger.info("  賣出 %s %s @ %.2f（%s）報酬 %+.2f%%", p.symbol, WATCHLIST.get(p.symbol, ""),
                            p.exit_price, p.exit_reason, p.net_return * 100)
            for p in result.opened:
                logger.info("  買進 %s %s @ %.2f 金額 %s", p.symbol, WATCHLIST.get(p.symbol, ""), p.entry_price, f"{p.cost:,.0f}")
            for plan in result.resolved_plans:
                if plan.status not in ("executed", "pending"):
                    logger.info("  未執行 %s %s：%s", plan.action, plan.symbol, plan.status)
            for plan in result.new_plans:
                if plan.action == "sell":
                    logger.info("  ▶ 明日開盤賣出 %s %s（收盤 %.2f 跌破 %s）", plan.symbol, WATCHLIST.get(plan.symbol, ""),
                                plan.close_price, "停利線 %.2f" % plan.trailing_line if plan.trailing_line else "季線")
                else:
                    logger.info("  ▶ 明日開盤買進候選 #%d %s %s（收盤 %.2f，開盤高於 %.2f 則放棄）", plan.priority + 1,
                                plan.symbol, WATCHLIST.get(plan.symbol, ""), plan.close_price, plan.skip_above)


def main() -> None:
    argparse.ArgumentParser(description="交易計畫與往後實測（模擬帳戶）").parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-7s %(message)s", datefmt="%H:%M:%S")
    try:
        engine = create_engine(build_database_url(), pool_pre_ping=True, connect_args={"connect_timeout": 15})
        init_schema(engine)
    except ConfigError as exc:
        logger.error("設定錯誤：%s", exc)
        sys.exit(2)
    log_paper(run_paper(engine, WATCHLIST))
    engine.dispose()


if __name__ == "__main__":
    main()
