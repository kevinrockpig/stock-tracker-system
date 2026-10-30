"""上市 / 上櫃日期：證交所與櫃買中心的公司基本資料（開放資料）。

Yahoo 的日 K 可能包含正式上市（上櫃）之前的資料，例如興櫃期間（沒有漲跌幅限制）、
或合併改制前另一家公司的價格（例如日月光投控 2018-04-30 成立，Yahoo 卻有更早的資料）。
這些期間的價格不適合計算指標與回測，因此每檔股票只保留上市（上櫃）日起的資料。

ETF 不在公司基本資料中（沒有上市日期），不受限制。
從櫃買中心轉到證交所上市的股票，只查得到證交所的上市日期，之前在櫃買中心的資料也會一併排除（較保守）。
"""

import json
import logging
from datetime import date

from scraper.official import fetch

logger = logging.getLogger(__name__)

TWSE_COMPANIES_URL = "https://openapi.twse.com.tw/v1/opendata/t187ap03_L"
TPEX_COMPANIES_URL = "https://www.tpex.org.tw/openapi/v1/mopsfin_t187ap03_O"


def parse_listing_date(value: str | None) -> date | None:
    """公司基本資料的日期為西元 YYYYMMDD，例如 20180430。"""
    value = (value or "").strip()
    if len(value) != 8 or not value.isdigit():
        return None
    return date(int(value[:4]), int(value[4:6]), int(value[6:]))


def listing_dates_from(twse_records: list[dict], tpex_records: list[dict]) -> dict[tuple[str, str], date]:
    """（市場, 證券代號）→ 上市 / 上櫃日期。"""
    dates = {}
    for market, records, code_field, date_field in (
        ("TWSE", twse_records, "公司代號", "上市日期"),
        ("TPEX", tpex_records, "SecuritiesCompanyCode", "DateOfListing"),
    ):
        for record in records:
            listed = parse_listing_date(record.get(date_field))
            if listed:
                dates[(market, (record.get(code_field) or "").strip())] = listed
    return dates


def fetch_listing_dates() -> dict[tuple[str, str], date]:
    twse = json.loads(fetch(TWSE_COMPANIES_URL))
    tpex = json.loads(fetch(TPEX_COMPANIES_URL))
    return listing_dates_from(twse, tpex)
