"""上市 / 上櫃日期解析單元測試：python -m pytest（不連線網路）"""

from datetime import date

from scraper.listing import listing_dates_from, parse_listing_date


def test_parse_listing_date():
    assert parse_listing_date("20180430") == date(2018, 4, 30)
    assert parse_listing_date(" 19940905 ") == date(1994, 9, 5)
    assert parse_listing_date("") is None and parse_listing_date(None) is None and parse_listing_date("1070430") is None


def test_listing_dates_from_both_markets():
    twse = [{"公司代號": "3711", "上市日期": "20180430"}, {"公司代號": "9999", "上市日期": ""}]
    tpex = [{"SecuritiesCompanyCode": "1815", "DateOfListing": "20060123"}]
    assert listing_dates_from(twse, tpex) == {
        ("TWSE", "3711"): date(2018, 4, 30),
        ("TPEX", "1815"): date(2006, 1, 23),
    }
