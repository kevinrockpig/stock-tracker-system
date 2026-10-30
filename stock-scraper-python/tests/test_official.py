"""官方開放資料解析與備份單元測試：python -m pytest（不連線網路與資料庫）"""

import csv
import gzip
import io
import json
from datetime import date
from decimal import Decimal

from scraper.archive import archive_path, write_archive
from scraper.db import CORPORATE_ACTION_VALUE_COLUMNS, OFFICIAL_VALUE_COLUMNS
from scraper.official import (
    SOURCES,
    collect,
    keep_stocks_and_etfs,
    parse_decimal,
    parse_roc_date,
    parse_tpex_json,
    parse_twse_csv,
    parse_twse_json,
)

SOURCE = {source.key: source for source in SOURCES}

# 以下範例取自 2026-10-29 實際回傳的官方資料格式
TWSE_CSV = (
    "﻿日期,證券代號,證券名稱,成交股數,成交金額,開盤價,最高價,最低價,收盤價,漲跌價差,成交筆數\n"
    '"1151029","2330","台積電","18213802","44051234567","2420.00","2425.00","2390.00","2415.00","-10.0000","50123"\n'
    '"1151029","00400A","主動國泰動能高息","29745814","428344232","14.70","14.75","14.27","14.36","-0.3500","10539"\n'
    '"1151029","030001","某權證","1000","500","0.50","0.50","0.50","0.50","0.0000","1"\n'
).encode("utf-8")

TWSE_JSON = json.dumps([
    {"Date": "1151028", "Code": "00647L", "Name": "元大S&P500正2", "TradeVolume": "464", "TradeValue": "65482",
     "OpeningPrice": "", "HighestPrice": "", "LowestPrice": "", "ClosingPrice": "", "Change": "0.0000", "Transaction": "17"},
]).encode("utf-8")

TPEX_JSON = json.dumps([
    {"Date": "1151029", "SecuritiesCompanyCode": "1240", "CompanyName": "茂生農經", "Close": "54.00", "Change": "+0.10",
     "Open": "54.00", "High": "54.10", "Low": "54.00", "Average": "54.16", "TradingShares": "11251",
     "TransactionAmount": "609343", "TransactionNumber": "21", "NextReferencePrice": "54.00"},
    {"Date": "1151029", "SecuritiesCompanyCode": "00411A", "CompanyName": "主動統一前沿科技", "Close": "10.04",
     "Change": "-0.17 ", "Open": "10.11", "High": "10.11", "Low": "9.94", "Average": "10.00",
     "TradingShares": "8128244", "TransactionAmount": "81271168", "TransactionNumber": "1529", "NextReferencePrice": "10.04"},
    {"Date": "1151029", "SecuritiesCompanyCode": "712345", "CompanyName": "某上櫃權證", "Close": "---", "Change": "---",
     "Open": "---", "High": "---", "Low": "---", "Average": "---", "TradingShares": "0",
     "TransactionAmount": "0", "TransactionNumber": "0", "NextReferencePrice": "1.00"},
]).encode("utf-8")


def test_roc_date():
    assert parse_roc_date("1151029") == date(2026, 10, 29)
    assert parse_roc_date(" 990105 ") == date(2010, 1, 5)


def test_decimal_handles_signs_commas_and_empty_markers():
    assert parse_decimal("+0.10") == Decimal("0.10")
    assert parse_decimal("-0.17 ") == Decimal("-0.17")
    assert parse_decimal("2,415.00") == Decimal("2415.00")
    for empty in ("", "--", "---", None, "X"):
        assert parse_decimal(empty) is None
    assert parse_decimal("除息") is None  # 非數字文字不會讓整批失敗


def test_twse_csv_row():
    rows = parse_twse_csv(TWSE_CSV)
    tsmc = rows[0]
    assert tsmc["market"] == "TWSE" and tsmc["code"] == "2330" and tsmc["trade_date"] == date(2026, 10, 29)
    assert tsmc["close_price"] == Decimal("2415.00")
    assert tsmc["change"] == Decimal("-10.0000")
    assert tsmc["volume"] == 18213802 and tsmc["transactions"] == 50123
    assert tsmc["next_reference_price"] is None  # 證交所不提供


def test_no_trade_day_keeps_volume_but_prices_are_null():
    row = parse_twse_json(TWSE_JSON)[0]
    assert row["trade_date"] == date(2026, 10, 28)
    assert row["close_price"] is None and row["open_price"] is None
    assert row["volume"] == 464


def test_tpex_row_with_plus_sign_and_next_reference():
    rows = parse_tpex_json(TPEX_JSON)
    first = rows[0]
    assert first["market"] == "TPEX" and first["code"] == "1240"
    assert first["change"] == Decimal("0.10")
    assert first["next_reference_price"] == Decimal("54.00")
    assert rows[1]["change"] == Decimal("-0.17")


def test_keep_only_stocks_and_etfs():
    rows = parse_twse_csv(TWSE_CSV) + parse_tpex_json(TPEX_JSON)
    kept = [row["code"] for row in keep_stocks_and_etfs(rows)]
    assert kept == ["2330", "00400A", "1240", "00411A"]  # 排除 030001、712345 等權證


def test_parsed_rows_cover_all_value_columns():
    row = parse_twse_csv(TWSE_CSV)[0]
    assert set(OFFICIAL_VALUE_COLUMNS) <= set(row)


TWSE_ACTIONS = json.dumps([
    {"Date": "1151022", "Code": "0056", "Name": "元大高股息", "Exdividend": "息", "StockDividendRatio": "",
     "SubscriptionRatio": "", "SubscriptionPricePerShare": "", "CashDividend": "1.720000", "SharesOffered": "",
     "SharesEmpOwner": "", "SharesholderOwner": "", "StockHoldingRatio": ""},
    {"Date": "1151106", "Code": "00400A", "Name": "主動國泰動能高息", "Exdividend": "息", "StockDividendRatio": "",
     "SubscriptionRatio": "", "SubscriptionPricePerShare": "", "CashDividend": "", "SharesOffered": "",
     "SharesEmpOwner": "", "SharesholderOwner": "", "StockHoldingRatio": ""},
]).encode("utf-8")

TPEX_ACTIONS = json.dumps([
    {"ExRrightsExDividendDate": "1151020", "SecuritiesCompanyCode": "00931B", "CompanyName": "統一美債20年",
     "ExRrightsExDividend": "除息", "StockDividendRatio": "0.00000000", "SubscriptionRatioToNewSharesIssued": "0.00000000",
     "SubscriptionPricePerShare": "0.00", "CashDividend": "0.14100000", "AllocatedForPublicUnderwriting": "0",
     "SubscribedByEmployees": "0", "SubscribedByExistingShareholders": "0", "SubscribedProRataInThousandShares": "0.00000000"},
    {"ExRrightsExDividendDate": "1151105", "SecuritiesCompanyCode": "6488", "CompanyName": "某上櫃公司",
     "ExRrightsExDividend": "除權息", "StockDividendRatio": "0.05000000", "CashDividend": "2.00000000"},
    {"ExRrightsExDividendDate": "", "SecuritiesCompanyCode": "", "CompanyName": "", "ExRrightsExDividend": ""},
]).encode("utf-8")


def test_twse_action_notice():
    records, rows = collect(SOURCE["twse_actions"], TWSE_ACTIONS)
    assert rows[0]["ex_date"] == date(2026, 10, 22) and rows[0]["action_type"] == "息"
    assert rows[0]["cash_dividend"] == Decimal("1.720000")
    assert rows[1]["cash_dividend"] is None  # 預告初期金額可能尚未公布
    assert rows[0]["raw"] is records[0]  # 官方原始欄位完整保留
    assert set(CORPORATE_ACTION_VALUE_COLUMNS) <= set(rows[0])


def test_tpex_action_type_is_normalized_and_blank_rows_are_skipped():
    _, rows = collect(SOURCE["tpex_actions"], TPEX_ACTIONS)
    assert [r["code"] for r in rows] == ["00931B", "6488"]  # 沒有代號的空白列被略過
    assert [r["action_type"] for r in rows] == ["息", "權息"]  # 「除息」→「息」、「除權息」→「權息」
    assert rows[1]["stock_dividend_ratio"] == Decimal("0.05000000")


def test_collect_keeps_raw_records_aligned_with_rows():
    records, rows = collect(SOURCE["tpex_openapi"], TPEX_JSON)
    assert [r["SecuritiesCompanyCode"] for r in records] == [row["code"] for row in rows] == ["1240", "00411A"]


def test_archive_is_written_once_and_kept_byte_identical(tmp_path):
    records, _ = collect(SOURCE["twse_csv"], TWSE_CSV)
    day = date(2026, 10, 29)

    assert write_archive(tmp_path, "twse_csv", day, records) is True
    path = archive_path(tmp_path, "twse_csv", day)
    assert path == tmp_path / "twse_csv" / "2026" / "2026-10-29.csv.gz"
    first_bytes = path.read_bytes()

    # 內容相同再寫一次：不改寫檔案（位元組完全相同，Git 不會出現變更）
    assert write_archive(tmp_path, "twse_csv", day, records) is False
    assert path.read_bytes() == first_bytes

    # 解壓後為原始欄位的 CSV，可直接用 Excel 或重新匯入
    rows = list(csv.DictReader(io.StringIO(gzip.decompress(first_bytes).decode("utf-8"))))
    assert [r["證券代號"] for r in rows] == ["2330", "00400A"]
    assert rows[0]["收盤價"] == "2415.00"


def test_archive_rewrites_when_content_changes(tmp_path):
    records, _ = collect(SOURCE["twse_csv"], TWSE_CSV)
    day = date(2026, 10, 29)
    write_archive(tmp_path, "twse_csv", day, records)
    changed = [dict(records[0], 收盤價="2420.00")] + records[1:]
    assert write_archive(tmp_path, "twse_csv", day, changed) is True
