"""策略掃描單元測試：python -m pytest"""

import datetime as dt
from decimal import Decimal

import numpy as np
import pandas as pd

from scraper.strategies import STRATEGIES, evaluate_strategies, to_signal_set


def make_inputs(n, **columns):
    """產生 n 個交易日的策略輸入；未指定的欄位以 NaN 填入。"""
    start = dt.date(2026, 1, 5)
    data = {"trade_date": [start + dt.timedelta(days=i) for i in range(n)]}
    for name in ("close_price", "ma5", "ma10", "ma20", "ma60", "macd_dif", "macd_signal"):
        data[name] = columns.get(name, [np.nan] * n)
    return pd.DataFrame(data)


def matched_days(result, strategy):
    return [i for i, ok in enumerate(result[strategy]) if ok]


def test_ma_bullish_requires_strict_order_of_all_four_lines():
    inputs = make_inputs(
        4,
        ma5=[13, 13, 13, 13],
        ma10=[12, 12, 12, 12],
        ma20=[11, 13, 11, 11],  # 第 1 天 MA20 = MA5，不符合
        ma60=[10, 10, 12, np.nan],  # 第 2 天 MA60 > MA20；第 3 天 MA60 缺值
    )
    assert matched_days(evaluate_strategies(inputs), "ma_bullish") == [0]


def test_golden_cross_only_on_the_crossing_day():
    # DIF：低於 → 低於 → 穿越（第 2 天）→ 持續在上（第 3 天不算）→ 跌破 → 再穿越（第 5 天）
    inputs = make_inputs(
        6,
        macd_dif=[-2, -1, 1, 2, -1, 1],
        macd_signal=[0, 0, 0, 0, 0, 0],
    )
    assert matched_days(evaluate_strategies(inputs), "golden_cross") == [2, 5]


def test_golden_cross_counts_touch_then_cross():
    # 前一天 DIF 剛好等於 MACD，隔天站上 → 視為交叉
    inputs = make_inputs(3, macd_dif=[-1, 0, 1], macd_signal=[0, 0, 0])
    assert matched_days(evaluate_strategies(inputs), "golden_cross") == [2]


def test_break_ma20_only_on_the_breakout_day():
    inputs = make_inputs(
        5,
        close_price=[99, 100, 101, 102, 99],
        ma20=[100, 100, 100, 100, 100],
    )
    # 第 1 天收盤 = MA20（未突破）、第 2 天由 100 → 101 突破、第 3 天持續在上不算
    assert matched_days(evaluate_strategies(inputs), "break_ma20") == [2]


def test_missing_previous_day_value_is_not_a_signal():
    # 暖身期結束的第一天，前一天指標為 NULL，不能算成交叉或突破
    inputs = make_inputs(
        2,
        close_price=[99, 101],
        ma20=[np.nan, 100],
        macd_dif=[np.nan, 1],
        macd_signal=[np.nan, 0],
    )
    result = evaluate_strategies(inputs)
    assert not result["golden_cross"].any()
    assert not result["break_ma20"].any()


def test_accepts_decimal_and_none_from_database():
    inputs = make_inputs(
        2,
        close_price=[Decimal("99"), Decimal("101")],
        ma20=[Decimal("100"), Decimal("100")],
        ma5=[None, Decimal("1")],
    )
    assert matched_days(evaluate_strategies(inputs), "break_ma20") == [1]


def test_signal_set_contains_only_matches():
    inputs = make_inputs(3, macd_dif=[-1, 1, 2], macd_signal=[0, 0, 0], close_price=[1, 3, 3], ma20=[2, 2, 2])
    result = evaluate_strategies(inputs)
    day = inputs["trade_date"][1]
    assert to_signal_set(result) == {(day, "golden_cross"), (day, "break_ma20")}
    assert set(result.columns) - {"trade_date"} == set(STRATEGIES)
