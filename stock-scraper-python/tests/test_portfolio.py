"""投資組合回測單元測試：python -m pytest（以手工資料驗證，不連線資料庫）"""

import math
from datetime import date, timedelta

import pytest

from scraper.backtest import EXIT_RULES, Bars, entry_signals, equity_curve, run_strategy
from scraper.portfolio import Stock, better_than, risk_metrics, simulate_portfolio, split_metrics

DAY0 = date(2026, 1, 5)


def stock(opens, closes, signals, dividends=None, ma60=None, symbol_etf=False):
    days = [DAY0 + timedelta(days=i) for i in range(len(closes))]
    bars = Bars(
        dates=days, open=list(map(float, opens)), close=list(map(float, closes)), atr=[1.0] * len(closes),
        ma60=[math.nan] * len(closes) if ma60 is None else list(map(float, ma60)),
        dividends={days[i]: d for i, d in (dividends or {}).items()}, is_etf=symbol_etf, start=0,
    )
    return Stock(bars, {d: i for i, d in enumerate(days)}, {"golden_cross": set(signals)})


def test_single_stock_with_one_slot_matches_per_stock_equity_curve():
    # 沒有搶位子的情況下，投資組合資金曲線應與個別回測的資金曲線完全相同（含股利、開盤出場）
    opens = [10, 10, 11, 12, 13, 12, 11, 10, 10, 11, 12, 13, 14]
    closes = [10, 11, 12, 13, 12, 11, 10, 10, 11, 12, 13, 14, 15]
    s = stock(opens, closes, [0, 7], dividends={3: 0.5})
    for code in ("hold_5", "trailing_atr", "trailing_nocap"):
        result = simulate_portfolio({"A.TW": s}, "golden_cross", code, seed=0, slots=1)
        trades = run_strategy(s.bars, sorted(s.entries["golden_cross"]), "golden_cross", EXIT_RULES[code])
        assert result["equity"] == pytest.approx(equity_curve(s.bars, trades)), code


def test_more_signals_than_slots_are_skipped():
    a = stock([10] * 8, [10] * 8, [0])
    b = stock([20] * 8, [20] * 8, [0])
    result = simulate_portfolio({"A.TW": a, "B.TW": b}, "golden_cross", "hold_5", seed=0, slots=1)
    assert result["trades"] == 1 and result["skipped"] == 1


def test_each_position_gets_one_slot_of_equity():
    # 兩個空位、兩檔同時進場：各用一半資金；價格不動，只付出成本
    a = stock([10] * 8, [10] * 8, [0])
    b = stock([20] * 8, [20] * 8, [0])
    result = simulate_portfolio({"A.TW": a, "B.TW": b}, "golden_cross", "hold_5", seed=0, slots=2)
    assert result["trades"] == 2 and result["skipped"] == 0
    assert result["invested"][1] == pytest.approx(1.0)  # 進場當天現金全數投入
    assert result["equity"][1] == pytest.approx(1 / (1 + 0.001425))  # 價格不動，只少了買進手續費
    round_trip = (1 - 0.001425 - 0.003) / (1 + 0.001425)
    assert result["equity"][5] == pytest.approx(round_trip)  # 第 5 天收盤兩檔都賣出


def test_risk_metrics_and_halves():
    days = [date(2020, 1, 1), date(2021, 1, 1), date(2022, 1, 1)]
    m = risk_metrics(days, [1.0, 2.0, 1.5])
    assert m["max_drawdown"] == pytest.approx(-0.25)
    assert m["cagr"] == pytest.approx(1.5 ** (365.25 / 731) - 1)
    assert m["calmar"] == pytest.approx(m["cagr"] / 0.25)
    first, second = split_metrics(days, [1.0, 2.0, 1.5], date(2021, 1, 1))
    assert first["cagr"] is None  # 前段只有一個交易日
    assert second["max_drawdown"] == pytest.approx(-0.25)


def test_better_than_requires_every_period():
    good = {"calmar": 1.2}
    bad = {"calmar": 0.8}
    base = {"calmar": 1.0}
    assert better_than((good, good, good), (base, base, base))
    assert not better_than((good, good, bad), (base, base, base))
    assert not better_than((good, {"calmar": None}, good), (base, base, base))


def test_state_strategy_entries_only_first_day():
    assert entry_signals([3, 4, 5], state=True) == [3]
