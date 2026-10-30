"""官方開放資料每日累積：臺灣證券交易所（上市）與證券櫃檯買賣中心（上櫃）。

執行：python -m scraper.official

累積兩類資料：
  - 個股日成交資訊 → official_daily_quotes
  - 除權除息預告表 → official_corporate_actions
每個來源同時寫入 CSV 備份（見 scraper/archive.py），最後顯示資料庫用量。

- 資料授權：政府資料開放授權條款－第 1 版（https://data.gov.tw/license），對外顯示時須依條款標示來源
- 開放資料只提供「當下」的資料，沒有歷史查詢：每天都要執行，漏掉的日子無法補回
- 只保留股票與 ETF（排除權證等），原樣保存，不做除權息 / 分割調整
- 證交所在除權息日的「漲跌價差」為 0：事件日以「漲跌價差 ≠ 收盤 − 前日收盤」偵測，
  事件類型與現金股利由除權除息預告表判斷（見 sql/verify.sql 查詢 17）
- 重複執行不會產生重複資料；休市日執行會取得前一交易日的資料，結果為「未變動」
"""

import argparse
import csv
import io
import json
import logging
import os
import re
import sys
import urllib.request
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal, InvalidOperation
from typing import Callable
from zoneinfo import ZoneInfo

from sqlalchemy import create_engine
from sqlalchemy.exc import SQLAlchemyError

from scraper.archive import archive_dir, write_archive
from scraper.config import ConfigError, build_database_url, env_file_permission_warning
from scraper.db import (
    SchemaMismatchError,
    database_size_bytes,
    init_schema,
    upsert_corporate_actions,
    upsert_official_quotes,
)

logger = logging.getLogger("scraper.official")

TIMEOUT_SECONDS = 30
TAIPEI = ZoneInfo("Asia/Taipei")  # GitHub Actions 主機為 UTC，日期一律以台灣時間判斷

# 4 碼為普通股（含 0050 這類 4 碼 ETF）；00 開頭的 5～6 碼為 ETF / ETN（例如 006208、00400A）。
# 權證（6 碼、非 00 開頭）與特別股（例如 2881A）不保留：權證每日上萬筆，且與 K 線、選股無關
KEEP_CODE = re.compile(r"^(\d{4}|00\d{3,4}[A-Z]?)$")

# 官方資料中代表「無資料」的寫法
EMPTY_VALUES = {"", "-", "--", "---", "----", "N/A", "X"}

# 資料庫用量提醒：Neon 免費方案每個 project 1 GB（2026-10 查證），可用環境變數 DB_STORAGE_LIMIT_MB 調整
DEFAULT_STORAGE_LIMIT_MB = 1024
STORAGE_WARNING_RATIO = 0.8


@dataclass(frozen=True)
class Source:
    key: str  # 寫入資料庫的 source 欄位，也是備份資料夾名稱
    market: str  # TWSE（上市）/ TPEX（上櫃）
    kind: str  # quotes（日成交資訊）/ actions（除權除息預告）
    label: str
    url: str
    read: Callable[[bytes], list[dict]]  # 原始回應 → 官方原始欄位的紀錄
    code_field: str  # 原始紀錄中的證券代號欄位
    to_row: Callable[[dict], dict]  # 原始紀錄 → 資料表欄位


# ---------- 數值解析 ----------

def parse_roc_date(value: str) -> date:
    """民國日期 1151029 → 2026-10-29。"""
    value = value.strip()
    return date(int(value[:-4]) + 1911, int(value[-4:-2]), int(value[-2:]))


def parse_decimal(value: str | None) -> Decimal | None:
    """'2,415.00'、'+0.10'、'-0.17 ' → Decimal；空白、'--' 等無資料寫法 → None。"""
    text = (value or "").strip().replace(",", "").replace("+", "")
    if text in EMPTY_VALUES:
        return None
    try:
        return Decimal(text)
    except InvalidOperation:
        return None


def parse_int(value: str | None) -> int | None:
    number = parse_decimal(value)
    return None if number is None else int(number)


def read_csv(payload: bytes) -> list[dict]:
    return list(csv.DictReader(io.StringIO(payload.decode("utf-8-sig"))))


def read_json(payload: bytes) -> list[dict]:
    return json.loads(payload)


# ---------- 日成交資訊 ----------

def quote_row(market: str, code: str, name: str, trade_date: date, *, open_, high, low, close, change,
              volume, trade_value, transactions, next_reference=None) -> dict:
    return {
        "market": market,
        "code": code.strip(),
        "name": name.strip(),
        "trade_date": trade_date,
        "open_price": parse_decimal(open_),
        "high_price": parse_decimal(high),
        "low_price": parse_decimal(low),
        "close_price": parse_decimal(close),
        # 證交所在除權息日的漲跌價差為 0，不可用來推算參考價（見 db.official_daily_quotes 的說明）
        "change": parse_decimal(change),
        "next_reference_price": parse_decimal(next_reference),
        "volume": parse_int(volume),
        "trade_value": parse_int(trade_value),
        "transactions": parse_int(transactions),
    }


def twse_csv_row(r: dict) -> dict:
    return quote_row(
        "TWSE", r["證券代號"], r["證券名稱"], parse_roc_date(r["日期"]),
        open_=r["開盤價"], high=r["最高價"], low=r["最低價"], close=r["收盤價"], change=r["漲跌價差"],
        volume=r["成交股數"], trade_value=r["成交金額"], transactions=r["成交筆數"],
    )


def twse_json_row(r: dict) -> dict:
    return quote_row(
        "TWSE", r["Code"], r["Name"], parse_roc_date(r["Date"]),
        open_=r["OpeningPrice"], high=r["HighestPrice"], low=r["LowestPrice"], close=r["ClosingPrice"],
        change=r["Change"], volume=r["TradeVolume"], trade_value=r["TradeValue"], transactions=r["Transaction"],
    )


def tpex_quote_row(r: dict) -> dict:
    return quote_row(
        "TPEX", r["SecuritiesCompanyCode"], r["CompanyName"], parse_roc_date(r["Date"]),
        open_=r["Open"], high=r["High"], low=r["Low"], close=r["Close"], change=r["Change"],
        volume=r["TradingShares"], trade_value=r["TransactionAmount"], transactions=r["TransactionNumber"],
        next_reference=r.get("NextReferencePrice"),
    )


def parse_twse_csv(payload: bytes) -> list[dict]:
    return [twse_csv_row(r) for r in read_csv(payload) if r.get("證券代號")]


def parse_twse_json(payload: bytes) -> list[dict]:
    return [twse_json_row(r) for r in read_json(payload)]


def parse_tpex_json(payload: bytes) -> list[dict]:
    return [tpex_quote_row(r) for r in read_json(payload)]


# ---------- 除權除息預告 ----------

def normalize_action_type(value: str) -> str:
    """證交所寫「權 / 息 / 權息」，櫃買中心寫「除權 / 除息 / 除權息」，統一為前者。"""
    return value.strip().replace("除", "")


def twse_action_row(r: dict) -> dict:
    return {
        "market": "TWSE",
        "code": r["Code"].strip(),
        "name": r["Name"].strip(),
        "ex_date": parse_roc_date(r["Date"]),
        "action_type": normalize_action_type(r["Exdividend"]),
        "cash_dividend": parse_decimal(r["CashDividend"]),
        "stock_dividend_ratio": parse_decimal(r["StockDividendRatio"]),
        "raw": r,
    }


def tpex_action_row(r: dict) -> dict:
    return {
        "market": "TPEX",
        "code": r["SecuritiesCompanyCode"].strip(),
        "name": r["CompanyName"].strip(),
        "ex_date": parse_roc_date(r["ExRrightsExDividendDate"]),
        "action_type": normalize_action_type(r["ExRrightsExDividend"]),
        "cash_dividend": parse_decimal(r["CashDividend"]),
        "stock_dividend_ratio": parse_decimal(r["StockDividendRatio"]),
        "raw": r,
    }


SOURCES = [
    Source("twse_csv", "TWSE", "quotes", "證交所 日成交 CSV",
           "https://www.twse.com.tw/exchangeReport/STOCK_DAY_ALL?response=open_data",
           read_csv, "證券代號", twse_csv_row),
    # OpenAPI 的更新時間可能比 CSV 晚一天，兩者都抓可互相補漏
    Source("twse_openapi", "TWSE", "quotes", "證交所 日成交 OpenAPI",
           "https://openapi.twse.com.tw/v1/exchangeReport/STOCK_DAY_ALL",
           read_json, "Code", twse_json_row),
    Source("tpex_openapi", "TPEX", "quotes", "櫃買中心 日成交 OpenAPI",
           "https://www.tpex.org.tw/openapi/v1/tpex_mainboard_daily_close_quotes",
           read_json, "SecuritiesCompanyCode", tpex_quote_row),
    Source("twse_actions", "TWSE", "actions", "證交所 除權除息預告",
           "https://openapi.twse.com.tw/v1/exchangeReport/TWT48U_ALL",
           read_json, "Code", twse_action_row),
    Source("tpex_actions", "TPEX", "actions", "櫃買中心 除權除息預告",
           "https://www.tpex.org.tw/openapi/v1/tpex_exright_prepost",
           read_json, "SecuritiesCompanyCode", tpex_action_row),
]


# ---------- 執行 ----------

def fetch(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "stock-tracker-system (open data daily archive)"})
    with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
        return response.read()


def keep_stocks_and_etfs(rows: list[dict]) -> list[dict]:
    return [row for row in rows if KEEP_CODE.match(row["code"])]


def collect(source: Source, payload: bytes) -> tuple[list[dict], list[dict]]:
    """回傳（官方原始紀錄, 資料表欄位），兩者一一對應，皆只含股票與 ETF。"""
    records = [r for r in source.read(payload) if KEEP_CODE.match((r.get(source.code_field) or "").strip())]
    return records, [source.to_row(r) for r in records]


def archive_records(source: Source, records: list[dict], rows: list[dict], today: date) -> int:
    """日成交依資料日期分檔；除權除息預告是「當天看到的整份預告表」，依執行日期分檔。回傳新增或變更的檔案數。"""
    groups = defaultdict(list)
    for record, row in zip(records, rows):
        groups[row["trade_date"] if source.kind == "quotes" else today].append(record)
    base = archive_dir()
    return sum(write_archive(base, source.key, day, group) for day, group in groups.items())


def log_result(source: Source, rows: list[dict], counts: dict, archived: int | None, today: date) -> None:
    db_part = f"新增 {counts['inserted']} / 更新 {counts['updated']} / 未變動 {counts['unchanged']}" if counts else "資料庫寫入失敗"
    archive_part = "備份失敗" if archived is None else f"備份檔新增或變更 {archived} 個"
    if source.kind == "quotes":
        dates = sorted({row["trade_date"] for row in rows})
        note = "" if dates[-1] == today else "（不是今天：休市或官方尚未更新，屬正常情況）"
        no_trade = sum(row["close_price"] is None for row in rows)
        logger.info("[%s] 資料日期 %s%s；%d 檔（%d 檔無成交）%s；%s",
                    source.label, "、".join(map(str, dates)), note, len(rows), no_trade, db_part, archive_part)
    else:
        dates = sorted({row["ex_date"] for row in rows})
        span = f"除權息日 {dates[0]} ~ {dates[-1]}" if dates else "目前沒有預告"
        logger.info("[%s] %d 筆（%s）%s；%s", source.label, len(rows), span, db_part, archive_part)


def log_storage(engine) -> None:
    limit_mb = float(os.getenv("DB_STORAGE_LIMIT_MB") or DEFAULT_STORAGE_LIMIT_MB)
    with engine.connect() as conn:
        size_mb = database_size_bytes(conn) / 1024 / 1024
    ratio = size_mb / limit_mb
    level = logging.WARNING if ratio >= STORAGE_WARNING_RATIO else logging.INFO
    hint = "，接近上限，請考慮清理或升級方案（超過上限後將無法寫入，資料不會被刪除）" if level == logging.WARNING else ""
    logger.log(level, "資料庫用量 %.1f MB / %.0f MB（%.1f%%）%s", size_mb, limit_mb, ratio * 100, hint)


def run() -> int:
    if warning := env_file_permission_warning():
        logger.warning(warning)
    try:
        url = build_database_url()
    except ConfigError as exc:
        logger.error("設定錯誤：%s", exc)
        return 2

    engine = create_engine(url, pool_pre_ping=True, connect_args={"connect_timeout": 15})
    try:
        init_schema(engine)
    except SchemaMismatchError as exc:
        logger.error("資料表結構不一致：%s", exc)
        return 3
    except SQLAlchemyError as exc:
        logger.error("資料庫連線或建表失敗：%s", getattr(exc, "orig", exc))
        return 3

    today = datetime.now(TAIPEI).date()
    logger.info("備份位置：%s", archive_dir())
    failed = []
    for source in SOURCES:
        try:
            records, rows = collect(source, fetch(source.url))
            if source.kind == "quotes" and not rows:
                raise ValueError("沒有任何股票或 ETF 資料（格式可能已變更）")
        except Exception as exc:  # 單一來源失敗不影響其他來源
            logger.error("[%s] 取得或解析失敗：%s", source.label, exc)
            failed.append(source.label)
            continue

        # 備份與寫入資料庫互相獨立：任一失敗都會記錄，另一個照常執行
        archived = None
        try:
            archived = archive_records(source, records, rows, today)
        except Exception as exc:
            logger.error("[%s] 備份失敗：%s", source.label, exc)
            failed.append(f"{source.label}（備份）")

        counts = None
        for row in rows:
            row["source"] = source.key
        try:
            with engine.begin() as conn:
                upsert = upsert_official_quotes if source.kind == "quotes" else upsert_corporate_actions
                counts = upsert(conn, rows)
        except Exception as exc:
            logger.error("[%s] 寫入資料庫失敗：%s", source.label, getattr(exc, "orig", exc))
            failed.append(f"{source.label}（資料庫）")

        log_result(source, rows, counts, archived, today)

    try:
        log_storage(engine)
    except Exception as exc:
        logger.warning("無法取得資料庫用量：%s", exc)

    engine.dispose()
    logger.info("官方資料累積完成：%d 個來源，失敗 %d 項 %s", len(SOURCES), len(failed), failed or "")
    return 1 if failed else 0


def main() -> None:
    argparse.ArgumentParser(description="累積證交所 / 櫃買中心每日開放資料（日成交與除權除息預告）").parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-7s %(message)s", datefmt="%H:%M:%S")
    sys.exit(run())


if __name__ == "__main__":
    main()
