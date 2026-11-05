"""組合模擬器 v2 的新功能：以小型的人工資料逐筆核對（與舊版一致的部分由 python -m research.validate 以真實資料比對）。"""

from datetime import date, timedelta

import pandas as pd
import pytest

from research.metrics import longest_underwater, yearly_returns
from research.simulator import ETF, ExitSpec, Plan, benchmark_plan, decision_days, simulate
from scraper.backtest import BUY_FEE, make_bars
from scraper.portfolio import Stock

STOCK_COST = 0.001425 + 0.003
ETF_COST = 0.001425 + 0.001


def weekdays(n: int, start: date = date(2024, 1, 1)) -> list[date]:  # 2024-01-01 是星期一
    days, day = [], start
    while len(days) < n:
        if day.weekday() < 5:
            days.append(day)
        day += timedelta(days=1)
    return days


def make_stock(symbol, closes, opens=None, atr=2.0, signals=None, dividends=None, dates=None):
    n = len(closes)
    dates = dates or weekdays(n)
    frame = pd.DataFrame({
        "trade_date": dates, "open_price": opens or closes, "close_price": closes,
        "atr14": atr if isinstance(atr, list) else [atr] * n, "ma60": [1.0] * n, "macd_signal": [0.0] * n,
    })
    bars = make_bars(symbol, frame, dividends or {})
    return Stock(bars, {d: i for i, d in enumerate(bars.dates)}, {code: set(idx) for code, idx in (signals or {}).items()})


def flat_etf(n, price=50.0, dates=None):
    return make_stock(ETF, [price] * n, dates=dates)


def buys_and_sells(result):
    return [(d, s, a, sh, p, r) for d, s, a, sh, p, r in result.log]


def test_percent_trailing_exit_with_whole_shares():
    closes = [100, 100, 110, 120, 115, 107, 105, 104]
    days = weekdays(len(closes))
    stocks = {"A.TW": make_stock("A.TW", closes, signals={"s": [0]}), ETF: flat_etf(len(closes))}
    result = simulate(stocks, Plan(("s",), ExitSpec("pct", pct=0.10), capital=100_000, slots=1))
    shares = 100_000 // (100 * (1 + BUY_FEE))
    # 最高收盤 120，跌破 108（120 × 0.9）的那天是 107 → 隔天開盤 105 賣出
    assert buys_and_sells(result) == [(days[1], "A.TW", "buy", shares, 100, None), (days[6], "A.TW", "sell", shares, 105, "trailing")]
    expected = 100_000 - shares * 100 * (1 + BUY_FEE) + shares * 105 * (1 - STOCK_COST)
    assert result.equity[-1] == pytest.approx(expected)


@pytest.mark.parametrize(("max_weight", "expected_amount"), [(0.2, 1_000_000 * 0.01 / 6 * 100), (0.1, 100_000)])
def test_risk_sizing_and_weight_cap(max_weight, expected_amount):
    # 停損距離 = 3 × ATR(2) = 6 元：每筆最多虧 1% → 可買 10,000 ÷ 6 股；單檔上限 10% 時改以 10 萬元為限
    stocks = {"A.TW": make_stock("A.TW", [100] * 5, signals={"s": [0]}), ETF: flat_etf(5)}
    plan = Plan(("s",), ExitSpec("atr", atr_multiple=3), capital=1_000_000, sizing="risk", risk_per_trade=0.01, max_weight=max_weight)
    buy = simulate(stocks, plan).log[0]
    assert buy[3] == expected_amount // (100 * (1 + BUY_FEE))


def test_take_profit_sells_half_then_trails():
    closes = [100, 100, 111, 112, 120, 100, 99]
    days = weekdays(len(closes))
    stocks = {"A.TW": make_stock("A.TW", closes, signals={"s": [0]}), ETF: flat_etf(len(closes))}
    result = simulate(stocks, Plan(("s",), ExitSpec("pct", pct=0.10, take_profit=0.10), capital=100_000, slots=1))
    shares = 100_000 // (100 * (1 + BUY_FEE))
    log = buys_and_sells(result)
    # 收盤 111 達到 +10% → 隔天開盤 112 先賣一半；之後最高 120，跌到 100 → 隔天開盤 99 賣出剩下的
    assert log[1] == (days[3], "A.TW", "sell", shares // 2, 112, "partial")
    assert log[2] == (days[6], "A.TW", "sell", shares - shares // 2, 99, "trailing")


def test_market_filter_blocks_new_entries():
    etf = make_stock(ETF, [100, 99, 98, 97, 96, 95, 94])  # 3 日均線：第 3 天起收盤都在均線之下
    stock = make_stock("A.TW", [100] * 7, signals={"s": [0, 3]})
    plan = Plan(("s",), ExitSpec("hold", hold_days=2), capital=100_000, slots=1, market_filter="no_new", filter_ma=3)
    result = simulate({"A.TW": stock, ETF: etf}, plan)
    # 第 0 天的訊號（大盤尚未轉弱）可以進場；第 3 天的訊號被擋下
    assert [entry[0] for entry in result.log if entry[2] == "buy"] == [weekdays(7)[1]]
    assert result.skipped == 1


def test_market_filter_exit_sells_satellite():
    etf = make_stock(ETF, [100, 100, 100, 100, 90, 90, 90])
    stock = make_stock("A.TW", [100] * 7, signals={"s": [0]})
    plan = Plan(("s",), ExitSpec("pct", pct=0.5), capital=100_000, slots=1, market_filter="exit", filter_ma=3)
    result = simulate({"A.TW": stock, ETF: etf}, plan)
    sell = [entry for entry in result.log if entry[2] == "sell"]
    assert sell[0][0] == weekdays(7)[5] and sell[0][5] == "market"  # 第 4 天收盤大盤跌破均線 → 第 5 天開盤賣出


def test_weekly_frequency_enters_next_week():
    closes = [100] * 10
    days = weekdays(10)
    stocks = {"A.TW": make_stock("A.TW", closes, signals={"s": [1]}), ETF: flat_etf(10)}  # 星期二出現訊號
    result = simulate(stocks, Plan(("s",), ExitSpec("pct", pct=0.5), capital=100_000, slots=1, frequency="weekly"))
    assert result.log[0][0] == days[5] == date(2024, 1, 8)  # 週五收盤判斷 → 下週一開盤買進
    assert decision_days(days, "weekly") == {date(2024, 1, 5), date(2024, 1, 12)}


def test_core_and_benchmark():
    n = 6
    etf = make_stock(ETF, [50] * n, dividends={weekdays(n)[3]: 1.0})
    core = simulate({ETF: etf}, Plan((), ExitSpec("hold", hold_days=1), capital=100_000, core_weight=1.0))
    bench = simulate({ETF: etf}, benchmark_plan(100_000))
    assert core.equity == bench.equity
    shares = 100_000 // (50 * (1 + BUY_FEE))
    assert bench.log == [(weekdays(n)[1], ETF, "buy", shares, 50, "core")]
    expected = 100_000 - shares * 50 * (1 + BUY_FEE) + shares * 1.0 + shares * 50 * (1 - ETF_COST)
    assert bench.equity[-1] == pytest.approx(expected)


def test_yearly_rebalance_restores_core_weight():
    days = [date(2024, 12, 27), date(2024, 12, 30), date(2024, 12, 31), date(2025, 1, 2), date(2025, 1, 3)]
    etf = make_stock(ETF, [50, 50, 100, 100, 100], dates=days)
    plan = Plan((), ExitSpec("hold", hold_days=1), capital=100_000, core_weight=0.5, rebalance="yearly")
    result = simulate({ETF: etf}, plan)
    rebalance = [entry for entry in result.log if entry[5] == "rebalance"]
    assert rebalance and rebalance[0][0] == date(2025, 1, 2) and rebalance[0][2] == "sell"  # 0050 漲一倍 → 年初賣回一半權重
    core_shares = sum(e[3] if e[2] == "buy" else -e[3] for e in result.log)
    assert core_shares * 100 == pytest.approx(result.equity[3] * 0.5, rel=0.01)


def test_metrics():
    days = [date(2024, 1, 1), date(2024, 6, 1), date(2024, 12, 31), date(2025, 3, 1), date(2025, 12, 31)]
    values = [100, 80, 110, 90, 99]
    assert longest_underwater(days, values) == (date(2024, 12, 31) - date(2024, 1, 1)).days  # 期末 99 仍低於 110，但只有 365 天
    assert yearly_returns(days, values) == {2024: pytest.approx(0.10), 2025: pytest.approx(99 / 110 - 1)}
