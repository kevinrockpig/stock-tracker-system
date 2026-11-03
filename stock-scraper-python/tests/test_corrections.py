"""股本事件人工校正的單元測試：python -m pytest（不連線網路與資料庫）"""

from datetime import date
from decimal import Decimal

from scraper.corrections import SHARE_CORRECTIONS, apply_share_corrections, corrections_for


def action(ex_date, cash=None, split=None, symbol="0050.TW"):
    return {"symbol": symbol, "ex_date": ex_date, "cash_dividend": cash, "split_ratio": split}


DIVIDEND = action(date(2025, 7, 21), cash=Decimal("0.36"))


def test_0050_split_is_added():
    result = apply_share_corrections("0050.TW", [DIVIDEND], stored_dates=set())
    assert result == [DIVIDEND, action(date(2025, 6, 18), split=Decimal("4"))]


def test_input_is_not_modified():
    records = [action(date(2025, 6, 18), cash=Decimal("1"))]
    apply_share_corrections("0050.TW", records, stored_dates=set())
    assert records[0]["split_ratio"] is None


def test_same_day_cash_dividend_keeps_amount():
    result = apply_share_corrections("0050.TW", [action(date(2025, 6, 18), cash=Decimal("1"))], stored_dates=set())
    assert result == [action(date(2025, 6, 18), cash=Decimal("1"), split=Decimal("4"))]


def test_already_stored_correction_is_written_again_unchanged():
    # 資料庫中已有這筆校正（同一天）：照樣補上，upsert 時為「未變動」
    result = apply_share_corrections("0050.TW", [], stored_dates={date(2025, 6, 18)})
    assert result == [action(date(2025, 6, 18), split=Decimal("4"))]


def test_skipped_when_yahoo_adds_the_event_on_another_day(caplog):
    yahoo = action(date(2025, 6, 11), split=Decimal("4"))
    result = apply_share_corrections("0050.TW", [yahoo], stored_dates=set())
    assert result == [yahoo]
    assert "略過 2025-06-18 的人工校正" in caplog.text


def test_skipped_when_database_already_has_yahoo_event_nearby():
    result = apply_share_corrections("0050.TW", [], stored_dates={date(2025, 6, 11), date(2025, 6, 18)})
    assert result == []


def test_far_away_share_change_does_not_block():
    other = action(date(2024, 6, 18), split=Decimal("1.1"))
    result = apply_share_corrections("0050.TW", [other], stored_dates=set())
    assert result == [other, action(date(2025, 6, 18), split=Decimal("4"))]


def test_different_yahoo_ratio_on_same_day_wins(caplog):
    yahoo = action(date(2025, 6, 18), split=Decimal("3"))
    assert apply_share_corrections("0050.TW", [yahoo], stored_dates=set()) == [yahoo]
    assert "與人工校正 4 不同" in caplog.text


def test_other_symbols_unchanged():
    records = [action(date(2025, 6, 18), cash=Decimal("1"), symbol="2330.TW")]
    assert corrections_for("2330.TW") == []
    assert apply_share_corrections("2330.TW", records, stored_dates=set()) == records


def test_corrections_are_documented():
    for c in SHARE_CORRECTIONS:
        assert c.split_ratio > 0 and c.source and c.symbol.endswith((".TW", ".TWO"))
