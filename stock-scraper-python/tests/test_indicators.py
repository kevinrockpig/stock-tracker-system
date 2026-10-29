"""技術指標單元測試：python -m pytest"""

import datetime as dt

import numpy as np
import pandas as pd
import pytest

from scraper.db import INDICATOR_COLUMNS
from scraper.indicators import RECURSIVE_COLUMNS, WARMUP_BARS, compute_indicators, taiwan_kd, to_indicator_records


def make_prices(closes, spread=1.0):
    """依收盤價產生日 K：最高 = 收盤 + spread、最低 = 收盤 − spread，日期為連續交易日。"""
    closes = np.asarray(closes, dtype=float)
    start = dt.date(2025, 1, 1)
    return pd.DataFrame({
        "trade_date": [start + dt.timedelta(days=i) for i in range(len(closes))],
        "high_price": closes + spread,
        "low_price": closes - spread,
        "close_price": closes,
    })


def test_taiwan_kd_matches_hand_calculation():
    # 以 N=3 手算：
    # 第 3 天 RSV = (12−8)/(12−8)×100 = 100 → K = 2/3×50 + 1/3×100 = 66.667，D = 2/3×50 + 1/3×66.667 = 55.556
    # 第 4 天 RSV = (11−9)/(13−9)×100 = 50  → K = 2/3×66.667 + 1/3×50 = 61.111，D = 2/3×55.556 + 1/3×61.111 = 57.407
    high = np.array([10, 11, 12, 13], dtype=float)
    low = np.array([8, 9, 10, 11], dtype=float)
    close = np.array([9, 10, 12, 11], dtype=float)

    k, d = taiwan_kd(high, low, close, period=3)

    assert np.isnan(k[:2]).all() and np.isnan(d[:2]).all()
    assert k[2] == pytest.approx(200 / 3)
    assert d[2] == pytest.approx(500 / 9)
    assert k[3] == pytest.approx(550 / 9)
    assert d[3] == pytest.approx(1550 / 27)


def test_taiwan_kd_flat_range_uses_rsv_50():
    flat = np.full(5, 100.0)
    k, d = taiwan_kd(flat, flat, flat, period=3)
    assert k[2:] == pytest.approx([50, 50, 50])
    assert d[2:] == pytest.approx([50, 50, 50])


def test_output_has_same_dates_and_schema_columns():
    prices = make_prices(np.linspace(100, 150, 200))
    result = compute_indicators(prices)

    assert len(result) == len(prices)
    assert list(result["trade_date"]) == list(prices["trade_date"])
    assert tuple(c for c in result.columns if c != "trade_date") == INDICATOR_COLUMNS


def test_moving_average_is_aligned_to_its_own_date():
    closes = np.arange(1, 101, dtype=float)
    result = compute_indicators(make_prices(closes))

    # MA5 第 5 筆（index 4）= 前 5 天收盤平均；在那之前資料不足為 NaN
    assert result["ma5"].iloc[:4].isna().all()
    assert result["ma5"].iloc[4] == pytest.approx(closes[:5].mean())
    assert result["ma60"].iloc[:59].isna().all()
    assert result["ma60"].iloc[99] == pytest.approx(closes[40:100].mean())


def test_recursive_indicators_are_null_during_warmup():
    result = compute_indicators(make_prices(np.linspace(100, 150, WARMUP_BARS + 20)))

    assert result.loc[: WARMUP_BARS - 1, list(RECURSIVE_COLUMNS)].isna().all().all()
    assert result.loc[WARMUP_BARS:, list(RECURSIVE_COLUMNS)].notna().all().all()


def test_rsi_is_100_when_price_only_rises():
    result = compute_indicators(make_prices(np.arange(1, WARMUP_BARS + 21, dtype=float)))
    assert result["rsi6"].iloc[-1] == pytest.approx(100)
    assert result["rsi12"].iloc[-1] == pytest.approx(100)


def test_records_round_to_4_decimals_and_convert_nan_to_none():
    result = compute_indicators(make_prices(np.linspace(100, 150, 10)))
    record = to_indicator_records("TEST", result)[-1]

    assert record["ma60"] is None
    assert str(record["ma5"]) == str(round(result["ma5"].iloc[-1], 4))
