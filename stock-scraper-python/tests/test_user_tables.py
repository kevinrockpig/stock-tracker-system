"""使用者資料表（app_users / user_identities / holdings / holding_sales 等）的結構：由本服務建立、Java API 寫入，雙方須一致。"""

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
    for table in (db.user_identities, db.holdings, db.holding_sales):
        fk = next(iter(table.c.user_id.foreign_keys))
        assert fk.column.table is db.app_users
        assert fk.ondelete == "CASCADE"


def test_holding_symbol_must_be_known_stock():
    for table in (db.holdings, db.holding_sales):
        fk = next(iter(table.c.symbol.foreign_keys))
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


def test_sale_columns_and_checks():
    # 賣出日期必填（先進先出依日期扣）；手續費方式與買進相同，另有證交稅方式
    nullable = {c.name for c in db.holding_sales.columns if c.nullable}
    assert nullable == {"fee_discount", "min_fee"}
    checks = _checks(db.holding_sales)
    assert set(checks) == {
        "ck_holding_sales_price", "ck_holding_sales_shares", "ck_holding_sales_fee", "ck_holding_sales_tax",
        "ck_holding_sales_min_fee", "ck_holding_sales_fee_mode", "ck_holding_sales_fee_discount", "ck_holding_sales_tax_mode",
    }
    for mode in db.HOLDING_FEE_MODES:
        assert f"'{mode}'" in checks["ck_holding_sales_fee_mode"]
    for mode in db.SALE_TAX_MODES:
        assert f"'{mode}'" in checks["ck_holding_sales_tax_mode"]
    # 與買進的手續費折數規則相同
    assert checks["ck_holding_sales_fee_discount"] == _checks(db.holdings)["ck_holdings_fee_discount"]


def test_user_tables_created_after_stocks():
    order = [t.name for t in db.metadata.sorted_tables]
    assert order.index("app_users") < order.index("holdings")
    assert order.index("stocks") < order.index("holdings")
    assert order.index("app_users") < order.index("holding_sales")


def test_spring_session_tables_match_official_schema():
    # 與 spring-session-jdbc 的 schema-postgresql.sql 相同（Java 以這些名稱查詢）
    session, attributes = db.spring_session, db.spring_session_attributes
    assert [c.name for c in session.columns] == [
        "primary_id", "session_id", "creation_time", "last_access_time", "max_inactive_interval", "expiry_time", "principal_name",
    ]
    assert {c.name for c in session.columns if c.nullable} == {"principal_name"}
    assert session.primary_key.name == "spring_session_pk"
    assert {(i.name, tuple(c.name for c in i.columns), i.unique) for i in session.indexes} == {
        ("spring_session_ix1", ("session_id",), True),
        ("spring_session_ix2", ("expiry_time",), False),
        ("spring_session_ix3", ("principal_name",), False),
    }
    assert [c.name for c in attributes.columns] == ["session_primary_id", "attribute_name", "attribute_bytes"]
    assert not any(c.nullable for c in attributes.columns)
    assert attributes.primary_key.name == "spring_session_attributes_pk"
    fk = next(iter(attributes.c.session_primary_id.foreign_keys))
    assert fk.column is session.c.primary_id and fk.ondelete == "CASCADE"


def test_notification_tables():
    # 使用者刪除時一併刪除通知設定、個股風險設定與發送紀錄
    for table in (db.notification_settings, db.holding_risk_levels, db.notification_log):
        fk = next(iter(table.c.user_id.foreign_keys))
        assert fk.column is db.app_users.c.id and fk.ondelete == "CASCADE"
    assert [c.name for c in db.holding_risk_levels.primary_key.columns] == ["user_id", "symbol"]
    assert ("user_id", "basis_date", "channel") in _unique_columns(db.notification_log)
    checks = _checks(db.holding_risk_levels)
    assert all(level in checks["ck_holding_risk_levels_level"] for level in ("conservative", "standard", "aggressive"))


def test_user_stocks_table():
    # 我的自選：刪除使用者時一併刪除；同一人同一檔一筆；代號格式與 yfinance 相同；不設外鍵到 stocks（加入時可能還沒有資料）
    fk = next(iter(db.user_stocks.c.user_id.foreign_keys))
    assert fk.column.table is db.app_users and fk.ondelete == "CASCADE"
    assert not db.user_stocks.c.symbol.foreign_keys
    assert ("user_id", "symbol") in _unique_columns(db.user_stocks)
    assert set(_checks(db.user_stocks)) == {"ck_user_stocks_symbol"}
    assert {c.name for c in db.user_stocks.columns if c.nullable} == set()


def test_user_plans_table():
    # 我的計畫：每人一筆、刪除使用者時一併刪除；版本與頻率的代碼與 Java 一致；投入金額不超過本金
    assert [c.name for c in db.user_plans.primary_key.columns] == ["user_id"]
    fk = next(iter(db.user_plans.c.user_id.foreign_keys))
    assert fk.column.table is db.app_users and fk.ondelete == "CASCADE"
    assert {c.name for c in db.user_plans.columns if c.nullable} == set()
    checks = _checks(db.user_plans)
    assert set(checks) == {"ck_user_plans_version", "ck_user_plans_frequency", "ck_user_plans_capital",
                           "ck_user_plans_max_loss", "ck_user_plans_invest"}
    for version in ("core", "balanced", "strategy"):
        assert f"'{version}'" in checks["ck_user_plans_version"]
    for frequency in ("daily", "weekly"):
        assert f"'{frequency}'" in checks["ck_user_plans_frequency"]
