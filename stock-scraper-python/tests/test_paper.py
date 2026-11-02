"""交易計畫（往後實測引擎）單元測試：python -m pytest（以手工資料驗證，不連線資料庫）"""

import math
from datetime import date, timedelta

import pytest

from scraper.backtest import Bars
from scraper.paper import Account, step_day, trading_days_after
from scraper.portfolio import Stock, simulate_portfolio

DAY0 = date(2026, 1, 5)


def stock(opens, closes, signals, dividends=None, atr=1.0, days=None, strategy="high_120"):
    days = days or [DAY0 + timedelta(days=i) for i in range(len(closes))]
    bars = Bars(
        dates=days, open=list(map(float, opens)), close=list(map(float, closes)), atr=[float(atr)] * len(closes),
        ma60=[math.nan] * len(closes), dividends={days[i]: d for i, d in (dividends or {}).items()}, is_etf=False, start=0,
    )
    return Stock(bars, {d: i for i, d in enumerate(days)}, {strategy: set(signals)})


def replay(stocks, slots, exit_rule="trailing_nocap", strategy="high_120"):
    """從第一個交易日啟用，逐日處理到最後一天；回傳每天收盤後的總資金（起始 1 元）與帳戶。"""
    days = sorted({d for s in stocks.values() for d in s.bars.dates})
    account = Account(strategy, exit_rule, slots, 1.0, days[0], days[0], 1.0, equity=1.0)
    last_close, curve, results = {}, [], []
    for day in days:
        result = step_day(account, day, stocks, last_close)
        curve.append(result.equity)
        results.append(result)
    return curve, account, results


def test_buy_next_open_then_trailing_sell_next_open():
    # 第 0 天訊號 → 第 1 天開盤 10 買進；最高收盤 13，ATR 1 → 停利線 10；第 5 天收盤 9.5 跌破 → 第 6 天開盤 9 賣出
    s = stock(opens=[10, 10, 11, 12, 12, 10, 9, 9], closes=[10, 11, 12, 13, 11, 9.5, 9, 9], signals=[0], atr=1.0)
    curve, account, results = replay({"A.TW": s}, slots=1)
    assert results[0].new_plans[0].action == "buy" and results[0].new_plans[0].skip_above == pytest.approx(10.95)
    assert results[1].opened[0].entry_price == 10
    sell_day = next(r for r in results if any(p.action == "sell" for p in r.new_plans))
    assert sell_day.day == DAY0 + timedelta(days=5)
    closed = results[6].closed[0]
    assert closed.exit_price == 9 and closed.exit_reason == "trailing"
    assert closed.net_return == pytest.approx(9 * (1 - 0.001425 - 0.003) / (10 * 1.001425) - 1)
    assert account.positions == [] and account.cash == pytest.approx(1 + closed.net_return)


def test_limit_up_open_is_skipped_and_recorded():
    s = stock(opens=[10, 10.96, 11, 11], closes=[10, 11, 11, 11], signals=[0])
    _, account, results = replay({"A.TW": s}, slots=1)
    assert results[1].opened == [] and results[1].resolved_plans[0].status == "skipped_limit_up"


def test_slot_limit_records_skipped_plan():
    a = stock(opens=[10] * 6, closes=[10] * 6, signals=[0])
    b = stock(opens=[20] * 6, closes=[20] * 6, signals=[0])
    _, account, results = replay({"A.TW": a, "B.TW": b}, slots=1)
    statuses = sorted(p.status for p in results[1].resolved_plans)
    assert statuses == ["executed", "skipped_no_slot"] and len(account.positions) == 1


def test_buy_waits_for_the_stock_next_bar_when_data_is_missing():
    # B 股缺第 1 天資料：買進計畫留到 B 的下一根 K 線（第 2 天）
    days = [DAY0 + timedelta(days=i) for i in range(5)]
    a = stock(opens=[10] * 5, closes=[10] * 5, signals=[], days=days)
    b = stock(opens=[20] * 4, closes=[20] * 4, signals=[0], days=[days[0], days[2], days[3], days[4]])
    _, account, results = replay({"A.TW": a, "B.TW": b}, slots=2)
    assert results[1].opened == [] and results[2].opened[0].entry_date == days[2]


def test_dividend_is_credited_on_ex_date():
    s = stock(opens=[10] * 6, closes=[10] * 6, signals=[0], dividends={3: 0.5})
    _, account, _ = replay({"A.TW": s}, slots=1)
    position = account.positions[0]
    assert position.dividends == pytest.approx(position.shares * 0.5)


def test_matches_portfolio_backtest_without_slot_competition():
    # 空位比股票多時不會有搶位子的情況：逐日引擎每天的總資金應與投資組合回測完全相同（最後一天回測以賣出成本計值，不比較）
    s1 = stock(opens=[10, 10, 11, 12, 13, 12, 11, 10, 10, 11, 12, 13, 14, 13, 12],
               closes=[10, 11, 12, 13, 12, 11, 10, 10, 11, 12, 13, 14, 15, 12, 11], signals=[0, 8], dividends={4: 0.3}, atr=1.5)
    s2 = stock(opens=[50, 51, 52, 50, 49, 48, 50, 52, 54, 55, 53, 52, 51, 50, 49],
               closes=[51, 52, 51, 49, 48, 49, 51, 53, 55, 54, 52, 51, 50, 49, 48], signals=[1, 6], atr=1.0)
    stocks = {"A.TW": s1, "B.TW": s2}
    curve, _, _ = replay(stocks, slots=5)
    expected = simulate_portfolio(stocks, "high_120", "trailing_nocap", seed=0, slots=5)["equity"]
    assert curve[:-1] == pytest.approx(expected[:-1])


def test_trading_days_after():
    s = stock(opens=[10] * 4, closes=[10] * 4, signals=[])
    assert trading_days_after({"A.TW": s}, DAY0 + timedelta(days=1)) == [DAY0 + timedelta(days=2), DAY0 + timedelta(days=3)]


def test_signal_on_close_exit_day_can_buy_again_next_day():
    # 持有 5 日：第 1 天進場、第 5 天收盤賣出；第 5 天又有訊號 → 第 6 天開盤再買進（與個別回測一致）
    s = stock(opens=[10] * 8, closes=[10] * 8, signals=[0, 5], strategy="golden_cross")
    _, account, results = replay({"A.TW": s}, slots=1, exit_rule="hold_5", strategy="golden_cross")
    assert [r.day for r in results if r.opened] == [DAY0 + timedelta(days=1), DAY0 + timedelta(days=6)]
