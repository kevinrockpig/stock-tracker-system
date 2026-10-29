"""資料表定義、建表與寫入。

資料表（皆由本服務建立與寫入，Java API 只讀取）：
  stocks            股票基本資料，一檔一筆
  stock_prices      日 K（OHLCV），同一股票同一交易日只保留一筆
  stock_indicators  技術指標，與 stock_prices 一對一
  strategy_signals  策略訊號，只存「某股票某交易日符合某策略」的組合
"""

import pandas as pd
from sqlalchemy import (
    BigInteger,
    Column,
    Date,
    DateTime,
    Engine,
    ForeignKey,
    ForeignKeyConstraint,
    Identity,
    Index,
    MetaData,
    Numeric,
    String,
    Table,
    UniqueConstraint,
    func,
    inspect,
    literal_column,
    select,
    tuple_,
)
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.engine import Connection

metadata = MetaData()

# 股票基本資料：一檔股票一筆
stocks = Table(
    "stocks",
    metadata,
    Column("symbol", String(20), primary_key=True),
    Column("name", String(100), nullable=False),
    Column("currency", String(10)),
    Column("created_at", DateTime(timezone=True), nullable=False, server_default=func.now()),
    Column("updated_at", DateTime(timezone=True), nullable=False, server_default=func.now()),
)

# 每日行情（日 K）：一檔股票一個交易日一筆；最新報價與歷史 K 線共用這張表
stock_prices = Table(
    "stock_prices",
    metadata,
    Column("id", BigInteger, Identity(), primary_key=True),
    Column("symbol", String(20), ForeignKey("stocks.symbol"), nullable=False),
    Column("trade_date", Date, nullable=False),
    Column("open_price", Numeric(12, 4)),
    Column("high_price", Numeric(12, 4)),
    Column("low_price", Numeric(12, 4)),
    Column("close_price", Numeric(12, 4), nullable=False),
    Column("volume", BigInteger),
    Column("updated_at", DateTime(timezone=True), nullable=False, server_default=func.now()),
    UniqueConstraint("symbol", "trade_date", name="uq_stock_prices_symbol_trade_date"),
)

PRICE_COLUMNS = ("open_price", "high_price", "low_price", "close_price", "volume")

# 技術指標欄位（由 indicators.compute_indicators 產生）
INDICATOR_COLUMNS = (
    "ma5", "ma10", "ma20", "ma60",
    "macd_dif", "macd_signal", "macd_osc",
    "rsi6", "rsi12",
    "kd_k", "kd_d",
)

# 技術指標：與 stock_prices 一對一，暖身期或資料不足時為 NULL
stock_indicators = Table(
    "stock_indicators",
    metadata,
    Column("id", BigInteger, Identity(), primary_key=True),
    Column("symbol", String(20), nullable=False),
    Column("trade_date", Date, nullable=False),
    *(Column(name, Numeric(12, 4)) for name in INDICATOR_COLUMNS),
    Column("updated_at", DateTime(timezone=True), nullable=False, server_default=func.now()),
    UniqueConstraint("symbol", "trade_date", name="uq_stock_indicators_symbol_trade_date"),
    # 每筆指標必定對應一筆日 K；日 K 被刪除（例如休市日補值）時指標一併刪除
    ForeignKeyConstraint(
        ["symbol", "trade_date"],
        ["stock_prices.symbol", "stock_prices.trade_date"],
        name="fk_stock_indicators_stock_prices",
        ondelete="CASCADE",
    ),
)


# 策略訊號：某檔股票在某個交易日符合某個策略（只存符合的組合）
strategy_signals = Table(
    "strategy_signals",
    metadata,
    Column("id", BigInteger, Identity(), primary_key=True),
    Column("symbol", String(20), nullable=False),
    Column("trade_date", Date, nullable=False),
    Column("strategy", String(30), nullable=False),
    Column("created_at", DateTime(timezone=True), nullable=False, server_default=func.now()),
    UniqueConstraint("symbol", "trade_date", "strategy", name="uq_strategy_signals_symbol_date_strategy"),
    ForeignKeyConstraint(
        ["symbol", "trade_date"],
        ["stock_prices.symbol", "stock_prices.trade_date"],
        name="fk_strategy_signals_stock_prices",
        ondelete="CASCADE",
    ),
    # 供「某策略在某日符合的股票」查詢使用
    Index("ix_strategy_signals_strategy_date", "strategy", "trade_date"),
)


class SchemaMismatchError(RuntimeError):
    """資料庫中已存在結構不同的同名資料表。"""


def init_schema(engine: Engine) -> None:
    """建立資料表（已存在則略過），並檢查既有資料表欄位是否一致。"""
    metadata.create_all(engine)

    inspector = inspect(engine)
    for table in (stocks, stock_prices, stock_indicators, strategy_signals):
        actual = {col["name"] for col in inspector.get_columns(table.name)}
        missing = {col.name for col in table.columns} - actual
        if missing:
            raise SchemaMismatchError(
                f"資料表 {table.name} 已存在但缺少欄位 {sorted(missing)}，"
                "可能是舊版程式建立的；確認資料可捨棄後請先 DROP 該表再重新執行"
            )


def upsert_stock(conn: Connection, symbol: str, name: str, currency: str | None) -> None:
    stmt = pg_insert(stocks).values(symbol=symbol, name=name, currency=currency)
    stmt = stmt.on_conflict_do_update(
        index_elements=[stocks.c.symbol],
        set_={"name": stmt.excluded.name, "currency": stmt.excluded.currency, "updated_at": func.now()},
    )
    conn.execute(stmt)


def delete_placeholder_rows(conn: Connection, symbol: str) -> int:
    """刪除先前已寫入的休市日補值資料（條件與 fetcher._clean 相同），回傳刪除筆數。

    對應的技術指標會因外鍵 ON DELETE CASCADE 一併刪除。
    """
    stmt = stock_prices.delete().where(
        stock_prices.c.symbol == symbol,
        stock_prices.c.volume == 0,
        stock_prices.c.open_price == stock_prices.c.close_price,
        stock_prices.c.high_price == stock_prices.c.close_price,
        stock_prices.c.low_price == stock_prices.c.close_price,
    )
    return conn.execute(stmt).rowcount


def upsert_prices(conn: Connection, rows: list[dict]) -> dict[str, int]:
    """寫入日行情，回傳新增 / 更新 / 未變動筆數（例如盤中資料被收盤資料取代時會更新）。"""
    return _upsert(conn, stock_prices, rows, "uq_stock_prices_symbol_trade_date", PRICE_COLUMNS)


def upsert_indicators(conn: Connection, rows: list[dict]) -> dict[str, int]:
    """寫入技術指標，回傳新增 / 更新 / 未變動筆數。"""
    return _upsert(conn, stock_indicators, rows, "uq_stock_indicators_symbol_trade_date", INDICATOR_COLUMNS)


def load_prices(conn: Connection, symbol: str) -> pd.DataFrame:
    """讀出資料庫中某檔股票的全部日 K（依日期由舊到新），作為指標計算的輸入。"""
    stmt = (
        select(stock_prices.c.trade_date, stock_prices.c.high_price, stock_prices.c.low_price, stock_prices.c.close_price)
        .where(stock_prices.c.symbol == symbol)
        .order_by(stock_prices.c.trade_date)
    )
    return pd.DataFrame(conn.execute(stmt).mappings().all())


def load_strategy_inputs(conn: Connection, symbol: str) -> pd.DataFrame:
    """策略掃描的輸入：收盤價 + 均線 + MACD（依日期由舊到新）。"""
    stmt = (
        select(
            stock_prices.c.trade_date,
            stock_prices.c.close_price,
            stock_indicators.c.ma5,
            stock_indicators.c.ma10,
            stock_indicators.c.ma20,
            stock_indicators.c.ma60,
            stock_indicators.c.macd_dif,
            stock_indicators.c.macd_signal,
        )
        .join(
            stock_indicators,
            (stock_indicators.c.symbol == stock_prices.c.symbol)
            & (stock_indicators.c.trade_date == stock_prices.c.trade_date),
        )
        .where(stock_prices.c.symbol == symbol)
        .order_by(stock_prices.c.trade_date)
    )
    return pd.DataFrame(conn.execute(stmt).mappings().all())


def sync_signals(conn: Connection, symbol: str, signals: set[tuple]) -> dict[str, int]:
    """讓資料庫中該股票的訊號與本次掃描結果一致：新增缺少的、刪除不再成立的（例如價格被修正）。

    signals 為 {(trade_date, strategy)}；回傳新增 / 刪除 / 未變動筆數。
    """
    existing = {
        (row.trade_date, row.strategy)
        for row in conn.execute(
            select(strategy_signals.c.trade_date, strategy_signals.c.strategy).where(strategy_signals.c.symbol == symbol)
        )
    }
    to_insert = signals - existing
    to_delete = existing - signals

    if to_insert:
        conn.execute(
            strategy_signals.insert(),
            [{"symbol": symbol, "trade_date": d, "strategy": s} for d, s in sorted(to_insert)],
        )
    for trade_date, strategy in to_delete:
        conn.execute(
            strategy_signals.delete().where(
                strategy_signals.c.symbol == symbol,
                strategy_signals.c.trade_date == trade_date,
                strategy_signals.c.strategy == strategy,
            )
        )
    return {"inserted": len(to_insert), "deleted": len(to_delete), "unchanged": len(signals & existing)}


def _upsert(conn: Connection, table: Table, rows: list[dict], constraint: str, value_columns: tuple[str, ...]) -> dict[str, int]:
    """以 (symbol, trade_date) 為鍵寫入。

    - 已存在 → 只有數值不同時才更新
    - 數值完全相同 → 不動，重複執行不會產生重複資料
    """
    if not rows:
        return {"inserted": 0, "updated": 0, "unchanged": 0}

    stmt = pg_insert(table).values(rows)
    current = tuple_(*(table.c[name] for name in value_columns))
    incoming = tuple_(*(stmt.excluded[name] for name in value_columns))
    stmt = stmt.on_conflict_do_update(
        constraint=constraint,
        set_={**{name: stmt.excluded[name] for name in value_columns}, "updated_at": func.now()},
        where=current.is_distinct_from(incoming),
    ).returning(
        # PostgreSQL 慣用技巧：新插入的列 xmax = 0，被 UPDATE 的列 xmax <> 0
        literal_column("(xmax = 0)").label("inserted")
    )

    flags = [row.inserted for row in conn.execute(stmt)]
    inserted = sum(flags)
    updated = len(flags) - inserted
    return {"inserted": inserted, "updated": updated, "unchanged": len(rows) - len(flags)}
