"""使用者資料表（app_users / user_identities / holdings）的結構：由本服務建立、Java API 寫入，雙方須一致。"""

from sqlalchemy import CheckConstraint, UniqueConstraint

from scraper import db


def _unique_columns(table):
    return {tuple(c.name for c in con.columns) for con in table.constraints if isinstance(con, UniqueConstraint)}


def _checks(table):
    return {con.name: str(con.sqltext) for con in table.constraints if isinstance(con, CheckConstraint)}


def test_one_user_per_email_and_identity():
    assert ("email",) in _unique_columns(db.app_users)
    assert ("provider", "subject") in _unique_columns(db.user_identities)


def test_user_data_deleted_with_user():
    for table in (db.user_identities, db.holdings):
        fk = next(iter(table.c.user_id.foreign_keys))
        assert fk.column.table is db.app_users
        assert fk.ondelete == "CASCADE"


def test_holding_symbol_must_be_known_stock():
    fk = next(iter(db.holdings.c.symbol.foreign_keys))
    assert fk.column is db.stocks.c.symbol


def test_holding_optional_columns():
    nullable = {c.name for c in db.holdings.columns if c.nullable}
    assert nullable == {"buy_date", "fee_discount", "min_fee"}


def test_holding_checks_cover_every_fee_mode():
    checks = _checks(db.holdings)
    assert set(checks) == {
        "ck_holdings_price", "ck_holdings_shares", "ck_holdings_fee", "ck_holdings_min_fee",
        "ck_holdings_fee_mode", "ck_holdings_fee_discount",
    }
    for mode in db.HOLDING_FEE_MODES:
        assert f"'{mode}'" in checks["ck_holdings_fee_mode"]


def test_user_tables_created_after_stocks():
    order = [t.name for t in db.metadata.sorted_tables]
    assert order.index("app_users") < order.index("holdings")
    assert order.index("stocks") < order.index("holdings")
