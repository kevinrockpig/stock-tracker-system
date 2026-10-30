"""日 K 擷取的資料處理單元測試：python -m pytest（不連線網路與資料庫）"""

from datetime import date
from decimal import Decimal

import pandas as pd

from scraper.fetcher import detect_readjustment, extract_actions, to_action_records


def raw_frame(rows):
    """模擬 yfinance history(actions=True) 的回傳：index 為台北時區的日期。"""
    index = pd.DatetimeIndex([pd.Timestamp(day, tz="Asia/Taipei") for day, *_ in rows])
    return pd.DataFrame(
        [{"Close": close, "Dividends": dividend, "Stock Splits": split} for _, close, dividend, split in rows],
        index=index,
    )


def test_extract_actions_keeps_only_event_days():
    raw = raw_frame([
        ("2018-07-24", 85.0, 0.0, 0.0),
        ("2018-07-25", 83.0, 2.5, 0.0),  # 現金股利
        ("2018-10-26", 70.0, 0.0, 0.8),  # 減資
        ("2025-06-18", 47.0, 1.0, 4.0),  # 同一天配息與分割
    ])
    actions = extract_actions(raw)
    assert list(actions["ex_date"]) == [date(2018, 7, 25), date(2018, 10, 26), date(2025, 6, 18)]
    records = to_action_records("2317.TW", actions)
    assert records[0] == {"symbol": "2317.TW", "ex_date": date(2018, 7, 25), "cash_dividend": Decimal("2.5"), "split_ratio": None}
    assert records[1]["cash_dividend"] is None and records[1]["split_ratio"] == Decimal("0.8")
    assert records[2]["cash_dividend"] == Decimal("1.0") and records[2]["split_ratio"] == Decimal("4.0")


def test_extract_actions_without_action_columns():
    raw = pd.DataFrame({"Close": [10.0]}, index=pd.DatetimeIndex([pd.Timestamp("2026-10-30", tz="Asia/Taipei")]))
    assert extract_actions(raw).empty


def fetched(closes):
    return pd.DataFrame({
        "trade_date": [date(2024, 11, 1 + i) for i in range(len(closes))],
        "close_price": closes,
    })


def test_no_readjustment_when_prices_match_or_differ_by_rounding():
    stored = {date(2024, 11, 1 + i): Decimal(str(v)) for i, v in enumerate([100.0, 101.0, 102.0])}
    assert detect_readjustment(fetched([100.0, 101.0, 102.0]), stored) is None
    assert detect_readjustment(fetched([100.0001, 101.0, 102.0]), stored) is None


def test_readjustment_detected_after_capital_reduction():
    # 減資 20%：Yahoo 把之前的價格全部除以 0.8
    stored = {date(2024, 11, 1 + i): Decimal(str(v)) for i, v in enumerate([80.0, 84.0, 88.0])}
    reason = detect_readjustment(fetched([100.0, 105.0, 110.0]), stored)
    assert reason is not None and "1.2500" in reason


def test_single_corrected_day_does_not_trigger_full_refetch():
    # 只有一天被 Yahoo 修正（例如資料更正）：中位數仍為 1，不需要重抓完整歷史
    stored = {date(2024, 11, 1 + i): Decimal(str(v)) for i, v in enumerate([100.0, 101.0, 102.0])}
    assert detect_readjustment(fetched([95.0, 101.0, 102.0]), stored) is None


def test_no_overlap_means_nothing_to_compare():
    assert detect_readjustment(fetched([100.0, 101.0]), {}) is None
