"""投資組合回測：一筆共用資金，依策略訊號在觀察名單中輪流進出場（scraper/backtest.py 是每檔股票各自一份資金）。

執行：python -m scraper.portfolio

規則事先固定，不做最佳化：
- 最多同時持有 SLOTS 檔；每次進場分配「前一日收盤的總資金 ÷ SLOTS」，現金不足時以剩餘現金為限
- 進出場與 backtest.simulate 完全相同（訊號隔日開盤進場、開盤近漲停略過、各出場規則、成本與股利）；
  同一檔股票持有中不重複進場；狀態型策略只在進入狀態的第一天進場
- 開盤賣出（移動停利、跌破季線）的資金，當天開盤即可用於新的進場；收盤賣出（持有期滿）的資金隔天才能用
- 同一天的訊號多於空位時，以隨機順序決定（SEEDS 個固定的亂數種子各算一次，取中位數，並列出範圍），
  避免「依代號」「依漲幅」等任意的排序變成另一個參數
- 現金股利在除息日入帳；資料到最新一天仍持有的部位，以當天收盤扣除賣出成本計值（與 backtest.equity_curve 一致）
- 假設可買零股（依金額買進，不受一張 1,000 股限制）

評估（標準 2：風險調整後是否勝過買進持有）：
- 指標：年化報酬、最大回撤、年化 ÷ 最大回撤（Calmar）、Sharpe（日報酬，無風險利率 0）、平均持股比例
- 基準：同一批股票等額買進持有（同樣有倖存者偏差，比較公平）；另列 0050 ETF 買進持有作參考
- 「風險調整後較佳」：整段期間、以及前後兩段（以 backtest 的切分日）的 Calmar 都高於同一批股票的買進持有
"""

import argparse
import logging
import math
import random
import statistics
import sys
from dataclasses import dataclass
from datetime import date

from sqlalchemy import create_engine

from scraper.backtest import (
    ALL_SCOPE,
    BUY_FEE,
    SELL_FEE,
    SELL_TAX_ETF,
    SELL_TAX_STOCK,
    Bars,
    buy_and_hold_curve,
    combined_curve,
    entry_signals,
    make_bars,
    rules_for,
    simulate,
)
from scraper.config import STOCK_GROUPS, WATCHLIST, ConfigError, build_database_url
from scraper.db import init_schema, load_backtest_inputs, load_split_date, sync_portfolio_stats
from scraper.strategies import STATE_STRATEGIES, STRATEGIES

logger = logging.getLogger(__name__)

SLOTS = 10
SEEDS = 10
ETF_BENCHMARK = "0050.TW"
OPEN_EXITS = {"trailing", "ma_break"}  # 這些出場在隔日開盤成交，其餘（hold、max_hold）在收盤成交
TRADING_DAYS_PER_YEAR = 252


@dataclass
class Stock:
    bars: Bars
    index_of: dict  # 日期 → bars 的位置
    entries: dict  # strategy → set(訊號日 index)，已套用狀態型策略「只算第一天」


@dataclass
class Position:
    symbol: str
    shares: float
    entry_date: date
    exit_date: date | None
    exit_price: float | None
    exit_at_open: bool


def sell_cost(symbol: str) -> float:
    return SELL_FEE + (SELL_TAX_ETF if symbol.split(".")[0].startswith("00") else SELL_TAX_STOCK)


def simulate_portfolio(stocks: dict[str, Stock], strategy: str, code: str, seed: int, slots: int = SLOTS) -> dict:
    """回傳 {dates, equity, invested, trades, skipped}；equity 為每個交易日收盤後的總資金（起始 1 元）。"""
    rule = rules_for(strategy)[code]
    rng = random.Random(seed)
    calendar = sorted({d for s in stocks.values() for d in s.bars.dates[s.bars.start:]})
    # 訊號日 → 隔一個交易日開盤進場：依「進場日」整理候選
    candidates = {}
    for symbol, stock in stocks.items():
        for i in stock.entries.get(strategy, ()):
            if stock.bars.start <= i < len(stock.bars.dates) - 1:
                candidates.setdefault(stock.bars.dates[i + 1], []).append((symbol, i))

    cash, equity = 1.0, 1.0
    positions: dict[str, Position] = {}
    last_close: dict[str, float] = {}
    dates, curve, invested = [], [], []
    trades = skipped = 0
    for day in calendar:
        # 1. 開盤賣出（與個別回測一致：同一檔當天賣出後不再當天買回）
        sold_today = set()
        for symbol in [s for s, p in positions.items() if p.exit_date == day and p.exit_at_open]:
            p = positions.pop(symbol)
            cash += p.shares * p.exit_price * (1 - sell_cost(symbol))
            sold_today.add(symbol)
        # 2. 開盤進場：空位不足時隨機決定順序
        todays = sorted(candidates.get(day, []))
        rng.shuffle(todays)
        budget = equity / slots
        for symbol, i in todays:
            if symbol in positions or symbol in sold_today:
                continue
            if len(positions) >= slots or cash <= 1e-12:
                skipped += 1
                continue
            trade = simulate(stocks[symbol].bars, i, rule)
            if trade is None:  # 開盤近漲停，買不到
                continue
            amount = min(budget, cash)
            cash -= amount
            positions[symbol] = Position(
                symbol, amount / (trade.entry_price * (1 + BUY_FEE)), trade.entry_date,
                trade.exit_date, trade.exit_price, trade.exit_reason in OPEN_EXITS,
            )
            trades += 1
        # 3. 除息日現金股利入帳（進場當天除息則領不到）
        for symbol, p in positions.items():
            amount = stocks[symbol].bars.dividends.get(day)
            if amount and day > p.entry_date:
                cash += p.shares * amount
        # 4. 收盤賣出
        for symbol in [s for s, p in positions.items() if p.exit_date == day and not p.exit_at_open]:
            p = positions.pop(symbol)
            cash += p.shares * p.exit_price * (1 - sell_cost(symbol))
        # 5. 收盤計值：當天沒有資料的股票沿用前一個收盤價
        for symbol, stock in stocks.items():
            i = stock.index_of.get(day)
            if i is not None:
                last_close[symbol] = stock.bars.close[i]
        holdings = sum(p.shares * last_close[s] for s, p in positions.items())
        equity = cash + holdings
        dates.append(day)
        curve.append(equity)
        invested.append(holdings / equity if equity > 0 else 0.0)
    # 最新一天仍持有：以收盤扣除賣出成本計值
    if positions and curve:
        curve[-1] = cash + sum(p.shares * last_close[s] * (1 - sell_cost(s)) for s, p in positions.items())
    return {"dates": dates, "equity": curve, "invested": invested, "trades": trades, "skipped": skipped}


def risk_metrics(dates: list[date], values: list[float]) -> dict:
    """年化報酬、最大回撤、Calmar（年化 ÷ |最大回撤|）、Sharpe（日報酬年化，無風險利率 0）。"""
    if len(values) < 2 or values[0] <= 0:
        return {"cagr": None, "max_drawdown": None, "calmar": None, "sharpe": None}
    years = (dates[-1] - dates[0]).days / 365.25
    growth = values[-1] / values[0]
    cagr = growth ** (1 / years) - 1 if years > 0 and growth > 0 else None
    peak, drawdown = values[0], 0.0
    for v in values:
        peak = max(peak, v)
        drawdown = min(drawdown, v / peak - 1)
    returns = [b / a - 1 for a, b in zip(values, values[1:]) if a > 0]
    sd = statistics.stdev(returns) if len(returns) > 1 else 0.0
    return {
        "cagr": cagr,
        "max_drawdown": drawdown,
        "calmar": cagr / -drawdown if cagr is not None and drawdown < 0 else None,
        "sharpe": statistics.fmean(returns) / sd * math.sqrt(TRADING_DAYS_PER_YEAR) if sd > 0 else None,
    }


def split_metrics(dates: list[date], values: list[float], split_date: date) -> tuple[dict, dict]:
    """前後兩段各自的指標（後段從切分日前一天的資金重新起算）。"""
    k = next((i for i, d in enumerate(dates) if d >= split_date), len(dates))
    first = risk_metrics(dates[:k], values[:k])
    second = risk_metrics(dates[max(k - 1, 0):], values[max(k - 1, 0):])
    return first, second


def benchmark_curve(stocks: dict[str, Stock], calendar: list[date]) -> list[float]:
    """同一批股票等額買進持有（不再平衡），對齊到投資組合的交易日。"""
    days, values = combined_curve([(s.bars.dates[s.bars.start:], buy_and_hold_curve(s.bars)) for s in stocks.values()])
    value_on = dict(zip(days, values))
    return [value_on[d] for d in calendar]


def better_than(strategy: tuple, benchmark: tuple) -> bool:
    """整段與前後兩段的 Calmar 都高於基準（標準 2）。"""
    pairs = list(zip(strategy, benchmark))
    return all(s["calmar"] is not None and b["calmar"] is not None and s["calmar"] > b["calmar"] for s, b in pairs)


def evaluate(stocks: dict[str, Stock], strategy: str, code: str, split_date: date, etf: Stock | None) -> dict:
    runs = [simulate_portfolio(stocks, strategy, code, seed) for seed in range(SEEDS)]
    calendar = runs[0]["dates"]
    per_seed = [risk_metrics(r["dates"], r["equity"]) for r in runs]
    # 以年化報酬的中位數那一次作為代表（指標彼此一致，不混用不同次的結果）
    order = sorted(range(SEEDS), key=lambda k: per_seed[k]["cagr"] if per_seed[k]["cagr"] is not None else -math.inf)
    median_run = runs[order[SEEDS // 2]]
    whole = risk_metrics(median_run["dates"], median_run["equity"])
    first, second = split_metrics(median_run["dates"], median_run["equity"], split_date)

    bench = benchmark_curve(stocks, calendar)
    bench_whole = risk_metrics(calendar, bench)
    bench_first, bench_second = split_metrics(calendar, bench, split_date)

    etf_metrics = {}
    if etf is not None:
        etf_dates = etf.bars.dates[etf.bars.start:]
        etf_metrics = risk_metrics(etf_dates, buy_and_hold_curve(etf.bars))

    cagrs = [m["cagr"] for m in per_seed if m["cagr"] is not None]
    return {
        "strategy": strategy, "exit_rule": code, "slots": SLOTS, "seeds": SEEDS,
        "period_start": calendar[0], "period_end": calendar[-1], "split_date": split_date,
        "trades": median_run["trades"], "skipped": median_run["skipped"],
        "avg_invested": statistics.fmean(median_run["invested"]),
        **whole,
        "cagr_min": min(cagrs) if cagrs else None, "cagr_max": max(cagrs) if cagrs else None,
        "calmar_first": first["calmar"], "calmar_second": second["calmar"],
        "bh_cagr": bench_whole["cagr"], "bh_max_drawdown": bench_whole["max_drawdown"],
        "bh_calmar": bench_whole["calmar"], "bh_sharpe": bench_whole["sharpe"],
        "bh_calmar_first": bench_first["calmar"], "bh_calmar_second": bench_second["calmar"],
        "etf_cagr": etf_metrics.get("cagr"), "etf_max_drawdown": etf_metrics.get("max_drawdown"),
        "etf_calmar": etf_metrics.get("calmar"),
        "beats_bh": better_than((whole, first, second), (bench_whole, bench_first, bench_second)),
    }


def load_stocks(conn, symbols, preloaded: dict | None = None) -> dict[str, Stock]:
    """preloaded：symbol → 回測輸入（db.backtest_inputs），有的就不再從資料庫讀取。"""
    preloaded = preloaded or {}
    stocks = {}
    for symbol in symbols:
        frame, signals, dividends = preloaded.get(symbol) or load_backtest_inputs(conn, symbol)
        if frame.empty:
            continue
        bars = make_bars(symbol, frame, dividends)
        index_of = {d: i for i, d in enumerate(bars.dates)}
        entries = {
            strategy: set(entry_signals([index_of[d] for d in days if d in index_of], strategy in STATE_STRATEGIES))
            for strategy, days in signals.items()
        }
        stocks[symbol] = Stock(bars, index_of, entries)
    return stocks


def run_portfolio(engine, symbols, split_date: date, preloaded: dict | None = None) -> list[dict]:
    with engine.connect() as conn:
        stocks = load_stocks(conn, symbols, preloaded)
    scopes = {ALL_SCOPE: list(stocks)}
    for group_code, group in STOCK_GROUPS.items():
        members = [s for s in group["stocks"] if s in stocks]
        if members:
            scopes[group_code] = members
    rows = []
    for scope, members in scopes.items():
        subset = {s: stocks[s] for s in members}
        for strategy in STRATEGIES:
            for code in rules_for(strategy):
                rows.append({"scope": scope, **evaluate(subset, strategy, code, split_date, stocks.get(ETF_BENCHMARK))})
    with engine.begin() as conn:
        sync_portfolio_stats(conn, rows)
    return rows


def _pct(value) -> str:
    return "—" if value is None else f"{value * 100:+.1f}%"


def _num(value) -> str:
    return "—" if value is None else f"{value:.2f}"


def log_portfolio(rows: list[dict]) -> None:
    for scope in dict.fromkeys(r["scope"] for r in rows):
        group = [r for r in rows if r["scope"] == scope]
        first = group[0]
        logger.info(
            "===== 投資組合回測 %s（最多 %d 檔，%s ~ %s）買進持有：年化 %s 回撤 %s Calmar %s（前 %s / 後 %s）；0050：年化 %s 回撤 %s Calmar %s =====",
            scope, SLOTS, first["period_start"], first["period_end"], _pct(first["bh_cagr"]), _pct(first["bh_max_drawdown"]),
            _num(first["bh_calmar"]), _num(first["bh_calmar_first"]), _num(first["bh_calmar_second"]),
            _pct(first["etf_cagr"]), _pct(first["etf_max_drawdown"]), _num(first["etf_calmar"]),
        )
        for r in group:
            logger.info(
                "  %-12s %-14s 年化 %s（%s ~ %s）回撤 %s Calmar %s（前 %s / 後 %s）Sharpe %s 持股 %.0f%% 交易 %d 略過 %d %s",
                STRATEGIES[r["strategy"]], r["exit_rule"], _pct(r["cagr"]), _pct(r["cagr_min"]), _pct(r["cagr_max"]),
                _pct(r["max_drawdown"]), _num(r["calmar"]), _num(r["calmar_first"]), _num(r["calmar_second"]),
                _num(r["sharpe"]), r["avg_invested"] * 100, r["trades"], r["skipped"],
                "★風險調整後較佳" if r["beats_bh"] else "",
            )


def main() -> None:
    argparse.ArgumentParser(description="投資組合回測（共用資金、最多同時持有 SLOTS 檔）").parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-7s %(message)s", datefmt="%H:%M:%S")
    try:
        engine = create_engine(build_database_url(), pool_pre_ping=True, connect_args={"connect_timeout": 15})
        init_schema(engine)
    except ConfigError as exc:
        logger.error("設定錯誤：%s", exc)
        sys.exit(2)
    with engine.connect() as conn:  # 與個別交易回測使用同一個切分日
        split_date = load_split_date(conn)
    if split_date is None:
        logger.error("尚未有回測統計（切分日），請先執行 python -m scraper.backtest")
        sys.exit(1)
    log_portfolio(run_portfolio(engine, WATCHLIST, split_date))
    engine.dispose()


if __name__ == "__main__":
    main()
