"""每日資料健康檢查：已知例外的排除、結果統計，以及每日更新的股票範圍（觀察名單＋庫存）。"""

from datetime import date

from scraper import health
from scraper.config import tracked_stocks


class _Row:
    def __init__(self, **values):
        self._values = values

    def _asdict(self):
        return dict(self._values)


class _FakeConn:
    """依查詢回傳預設資料列：rows_by_title[檢查名稱] → 資料列。"""

    def __init__(self, rows_by_title):
        self.rows_by_title = rows_by_title
        self._sql_to_title = {check.sql: check.title for check in health.CHECKS}

    def execute(self, statement):
        title = self._sql_to_title[statement.text]
        return [_Row(**row) for row in self.rows_by_title.get(title, [])]


def _check(title_prefix):
    return next(c for c in health.CHECKS if c.title.startswith(title_prefix))


def test_all_clear():
    results = health.run_checks(_FakeConn({}))
    assert len(results) == len(health.CHECKS)
    assert health.log_results(results) == 0


def test_known_exceptions_are_not_anomalies():
    big_move = _check("單日漲跌超過 10%")
    known_symbol, known_date = sorted(big_move.known)[0]
    rows = {big_move.title: [
        {"symbol": known_symbol, "trade_date": known_date, "change_pct": -10.5},
        {"symbol": "2330.TW", "trade_date": date(2026, 11, 3), "change_pct": 15.0},
    ]}
    result = next(r for r in health.run_checks(_FakeConn(rows)) if r.check is big_move)
    assert result.known == 1
    assert result.rows == [{"symbol": "2330.TW", "trade_date": date(2026, 11, 3), "change_pct": 15.0}]


def test_anomalies_counted_per_check():
    rows = {
        _check("股票缺少市場最新交易日").title: [
            {"symbol": "2330.TW", "last_date": date(2026, 10, 31)},
            {"symbol": "2454.TW", "last_date": date(2026, 10, 31)},
        ],
        _check("停牌期間沒有股數變動紀錄").title: [
            {"symbol": "0050.TW", "trade_date": date(2025, 6, 18), "last_trade_before": date(2025, 6, 10), "missed_days": 5},
        ],
    }
    assert health.log_results(health.run_checks(_FakeConn(rows))) == 2


def test_checks_are_read_only_selects():
    for check in health.CHECKS:
        sql = check.sql.strip().upper()
        assert sql.startswith(("SELECT", "WITH")), check.title
        for keyword in ("INSERT ", "UPDATE ", "DELETE ", "DROP ", "ALTER ", "TRUNCATE "):
            assert keyword not in sql, check.title


def test_tracked_stocks_adds_held_stocks_after_watchlist():
    watchlist = {"2330.TW": "台積電", "0050.TW": "元大台灣50"}
    held = {"0050.TW": "舊名稱", "1101.TW": "台泥"}
    tracked = tracked_stocks(watchlist, held)
    assert list(tracked) == ["2330.TW", "0050.TW", "1101.TW"]
    assert tracked["0050.TW"] == "元大台灣50"  # 觀察名單的名稱優先


def test_tracked_stocks_without_holdings():
    watchlist = {"2330.TW": "台積電"}
    assert tracked_stocks(watchlist, {}) == watchlist


def test_already_current_requires_prices_and_levels_for_every_symbol():
    from scraper.main import already_current
    targets = {"2330.TW": "台積電", "0050.TW": "元大台灣50"}
    both = {"2330.TW", "0050.TW"}
    assert already_current(targets, both, both)
    assert not already_current(targets, {"2330.TW"}, both)  # Yahoo 漏了 0050 今天的日 K → 照常重抓
    assert not already_current(targets, both, {"2330.TW"})  # 參考價位（最後一步）沒完成 → 照常執行
