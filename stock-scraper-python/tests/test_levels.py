"""參考價位單元測試：python -m pytest（以手工資料驗證，不連線資料庫）"""

from datetime import date, timedelta
from decimal import Decimal as D

import pandas as pd
import pytest

from scraper.levels import compute_levels, limit_prices, market_code, next_reference, next_weekday, tick_size


@pytest.mark.parametrize(
    "price, etf, tick",
    [
        ("9.99", False, "0.01"), ("10", False, "0.05"), ("49.95", False, "0.05"), ("50", False, "0.1"),
        ("100", False, "0.5"), ("500", False, "1"), ("999", False, "1"), ("1000", False, "5"),
        ("49.99", True, "0.01"), ("50", True, "0.05"), ("1200", True, "0.05"),
    ],
)
def test_tick_size(price, etf, tick):
    assert tick_size(D(price), etf) == D(tick)


@pytest.mark.parametrize(
    "reference, etf, up, down",
    [
        ("2370", False, "2605", "2135"),  # 2607 → 漲停向下取到 5 元；2133 → 跌停向上取到 5 元
        ("104.95", True, "115.40", "94.50"),  # ETF 50 元以上單位 0.05
        ("382", False, "420", "344"),  # 420.2 → 420；343.8 → 344（500 元以下單位 0.5）
        ("30.65", False, "33.70", "27.60"),
        ("9.5", False, "10.45", "8.55"),  # 漲停價跨過 10 元，以漲停價所在級距的單位（0.05）取整
    ],
)
def test_limit_prices(reference, etf, up, down):
    assert limit_prices(D(reference), etf) == (D(up), D(down))


def test_limit_prices_never_exceed_ten_percent():
    for cents in range(500, 300000, 37):
        reference = D(cents) / 100
        up, down = limit_prices(reference, etf=False)
        assert up <= reference * D("1.1") and down >= reference * D("0.9")


def test_next_weekday_skips_weekend():
    assert next_weekday(date(2026, 10, 30)) == date(2026, 11, 2)  # 週五 → 週一
    assert next_weekday(date(2026, 10, 28)) == date(2026, 10, 29)


def test_reference_price_on_next_day_ex_dividend():
    friday = date(2026, 10, 30)
    dividend = {"ex_date": date(2026, 11, 2), "action_type": "息", "cash_dividend": D("7")}
    assert next_reference(D("2370"), friday, dividend) == (D("2363"), True)
    # 除息日不是下一個交易日：參考價不變，只是提醒
    later = {**dividend, "ex_date": date(2026, 11, 12)}
    assert next_reference(D("2370"), friday, later) == (D("2370"), True)
    # 除權（配股或現金增資）或股利未公告：不自行計算參考價
    assert next_reference(D("50"), friday, {**dividend, "action_type": "權"}) == (D("50"), False)
    assert next_reference(D("50"), friday, {**dividend, "cash_dividend": None}) == (D("50"), False)


def bars(highs, lows, closes, atr="10"):
    start = date(2026, 1, 5)
    return pd.DataFrame({
        "trade_date": [start + timedelta(days=i) for i in range(len(closes))],
        "high_price": [D(str(v)) for v in highs],
        "low_price": [D(str(v)) for v in lows],
        "close_price": [D(str(v)) for v in closes],
        "atr14": [D(atr)] * len(closes),
    })


def test_fibonacci_in_uptrend_measures_pullback_from_high():
    # 低點 100 在前、高點 200 在後
    levels = compute_levels("2330.TW", bars([110, 150, 200, 190], [100, 120, 160, 170], [105, 140, 190, 180]), None)
    assert levels["swing_trend"] == "up" and levels["swing_low"] == 100 and levels["swing_high"] == 200
    assert (levels["fib_382"], levels["fib_500"], levels["fib_618"]) == (D("161.8"), D("150"), D("138.2"))


def test_fibonacci_in_downtrend_measures_bounce_from_low():
    levels = compute_levels("2330.TW", bars([200, 150, 120, 130], [180, 130, 100, 110], [190, 140, 105, 120]), None)
    assert levels["swing_trend"] == "down"
    assert (levels["fib_382"], levels["fib_500"], levels["fib_618"]) == (D("138.2"), D("150"), D("161.8"))


def test_swing_window_uses_last_60_days_only():
    highs = [500] + [110] * 60  # 第一天的高點已超出 60 個交易日
    levels = compute_levels("2330.TW", bars(highs, [100] * 61, [105] * 61), None)
    assert levels["swing_days"] == 60 and levels["swing_high"] == 110


def test_atr_stop_and_limits():
    levels = compute_levels("2330.TW", bars([2400], [2350], [2370], atr="45.5"), None)
    assert levels["atr_stop"] == D("2233.5")  # 2370 − 3 × 45.5
    assert (levels["limit_up"], levels["limit_down"], levels["reference_exact"]) == (D("2605"), D("2135"), True)
    assert levels["next_ex_date"] is None


def test_missing_atr_during_warmup():
    levels = compute_levels("2330.TW", bars([10], [9], [9.5], atr="NaN"), None)
    assert levels["atr14"] is None and levels["atr_stop"] is None


def test_market_code():
    assert market_code("2330.TW") == ("TWSE", "2330")
    assert market_code("6488.TWO") == ("TPEX", "6488")
