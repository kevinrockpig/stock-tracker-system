import json
from datetime import date

import pytest

from research import finmind


def test_deadline_is_tenth_of_next_month():
    assert finmind.deadline_of(2025, 3) == date(2025, 4, 10)
    assert finmind.deadline_of(2025, 12) == date(2026, 1, 10)


def test_defensive_stocks_only_listed_four_digit_codes():
    info = [
        {"stock_id": "2882", "type": "twse", "industry_category": "金融保險"},
        {"stock_id": "2882", "type": "twse", "industry_category": "金融保險"},  # 重複列只算一次
        {"stock_id": "5880", "type": "twse", "industry_category": "金融保險"},
        {"stock_id": "5820", "type": "tpex", "industry_category": "金融保險"},  # 上櫃不算
        {"stock_id": "2603", "type": "twse", "industry_category": "航運業"},
        {"stock_id": "00878", "type": "twse", "industry_category": "金融保險"},  # 非 4 碼
        {"stock_id": "2330", "type": "twse", "industry_category": "半導體業"},
    ]
    groups = finmind.defensive_stocks(info)
    assert groups["金融保險"] == ["2882", "5880"]
    assert groups["航運業"] == ["2603"]
    assert groups["電信"] == finmind.TELECOM
    assert "半導體業" not in groups


@pytest.fixture
def raw(tmp_path, monkeypatch):
    monkeypatch.setattr(finmind, "RAW_DIR", tmp_path)

    def write(dataset, data_id, rows):
        finmind._save(finmind._path(dataset, data_id), rows)

    write("TaiwanStockInfo", None, [{"stock_id": "2882", "stock_name": "國泰金", "type": "twse", "industry_category": "金融保險"}])
    return write


def test_load_nets_flows_by_investor_type(raw):
    raw("TaiwanStockInstitutionalInvestorsBuySell", "2882", [
        {"date": "2025-01-02", "stock_id": "2882", "name": "Foreign_Investor", "buy": 1000, "sell": 300},
        {"date": "2025-01-02", "stock_id": "2882", "name": "Foreign_Dealer_Self", "buy": 0, "sell": 100},
        {"date": "2025-01-02", "stock_id": "2882", "name": "Investment_Trust", "buy": 50, "sell": 0},
        {"date": "2025-01-02", "stock_id": "2882", "name": "Dealer_self", "buy": 10, "sell": 30},
        {"date": "2025-01-02", "stock_id": "2882", "name": "Dealer_Hedging", "buy": 5, "sell": 0},
    ])
    raw("TaiwanStockMonthRevenue", "2882", [
        {"date": "2025-02-01", "stock_id": "2882", "revenue": 200, "revenue_year": 2025, "revenue_month": 1},
        {"date": "2025-01-01", "stock_id": "2882", "revenue": 100, "revenue_year": 2024, "revenue_month": 12},
    ])
    raw("TaiwanStockTotalInstitutionalInvestors", None, [
        {"date": "2025-01-02", "name": "Foreign_Investor", "buy": 10.0, "sell": 4.0},
        {"date": "2025-01-02", "name": "total", "buy": 99.0, "sell": 0.0},  # 合計列不重複計入
    ])
    ext = finmind.load()
    assert ext.flows["2882"][date(2025, 1, 2)] == {"foreign": 600, "trust": 50, "dealer": -15}
    assert [(r.year, r.month, r.revenue, r.deadline) for r in ext.revenue["2882"]] == [
        (2024, 12, 100.0, date(2025, 1, 10)), (2025, 1, 200.0, date(2025, 2, 10))]
    assert ext.market[date(2025, 1, 2)] == {"foreign": 6.0, "trust": 0.0, "dealer": 0.0}
    assert ext.names["2882"] == "國泰金"


def test_load_without_download(tmp_path, monkeypatch):
    monkeypatch.setattr(finmind, "RAW_DIR", tmp_path)
    with pytest.raises(FileNotFoundError):
        finmind.load()


def test_save_is_atomic(tmp_path):
    path = tmp_path / "a" / "b.json"
    finmind._save(path, [{"x": 1}])
    assert json.loads(path.read_text(encoding="utf-8")) == [{"x": 1}]
    assert not path.with_suffix(".tmp").exists()
