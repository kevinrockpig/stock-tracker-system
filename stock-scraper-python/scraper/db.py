"""資料表定義、建表與寫入。

資料表（皆由本服務建立與寫入，Java API 只讀取）：
  stocks            股票基本資料，一檔一筆
  stock_prices      日 K（OHLCV），同一股票同一交易日只保留一筆
  stock_actions     除權息與股本事件（現金股利、配股 / 減資 / 分割），供回測計算還原報酬
  stock_indicators  技術指標，與 stock_prices 一對一
  strategy_signals  策略訊號，只存「某股票某交易日符合某策略」的組合
  backtest_trades   策略回測的每筆交易（見 backtest.py）
  backtest_stats    策略回測統計：每個（策略, 股票或全部, 出場規則）一筆
  reference_levels  參考價位：每檔股票每個交易日收盤後計算一筆（見 levels.py），保留歷史供日後檢視
  official_daily_quotes       證交所 / 櫃買中心開放資料的原始日成交資訊（每日累積，與上面幾張表獨立）
  official_corporate_actions  證交所 / 櫃買中心的除權除息預告（每日累積）
"""

import pandas as pd
from sqlalchemy import (
    BigInteger,
    Boolean,
    Column,
    Date,
    DateTime,
    Engine,
    ForeignKey,
    ForeignKeyConstraint,
    Identity,
    Index,
    Integer,
    MetaData,
    Numeric,
    String,
    Table,
    UniqueConstraint,
    func,
    inspect,
    literal_column,
    select,
    text,
    tuple_,
)
from sqlalchemy.dialects.postgresql import JSONB
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
    # 資料庫中完整歷史的起始設定（最近一次完整重抓時的 config.HISTORY_START）；NULL 表示尚未補齊歷史
    Column("history_from", Date),
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

# 除權息與股本事件（yfinance）：同一股票同一除權息日一筆，現金股利與股數變動可能同一天發生。
# Yahoo 會把配股、減資、分割之前的價格與股利一併等比例調整，因此這裡的金額與 stock_prices 的價格基準一致
stock_actions = Table(
    "stock_actions",
    metadata,
    Column("id", BigInteger, Identity(), primary_key=True),
    Column("symbol", String(20), ForeignKey("stocks.symbol"), nullable=False),
    Column("ex_date", Date, nullable=False),
    Column("cash_dividend", Numeric(14, 6)),  # 每股現金股利
    Column("split_ratio", Numeric(14, 6)),  # 新股數 / 舊股數：配股 1.1 = 每股配 0.1 股、減資 0.8、1 拆 4 = 4
    Column("created_at", DateTime(timezone=True), nullable=False, server_default=func.now()),
    Column("updated_at", DateTime(timezone=True), nullable=False, server_default=func.now()),
    UniqueConstraint("symbol", "ex_date", name="uq_stock_actions_symbol_ex_date"),
)

ACTION_COLUMNS = ("cash_dividend", "split_ratio")

# 技術指標欄位（由 indicators.compute_indicators 產生）
INDICATOR_COLUMNS = (
    "ma5", "ma10", "ma20", "ma60",
    "macd_dif", "macd_signal", "macd_osc",
    "rsi6", "rsi12",
    "kd_k", "kd_d",
    "atr14",
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


# 策略回測的每筆交易：同一（策略, 股票, 出場規則）以進場日識別；每天依最新資料重算並同步
backtest_trades = Table(
    "backtest_trades",
    metadata,
    Column("id", BigInteger, Identity(), primary_key=True),
    Column("strategy", String(30), nullable=False),
    Column("symbol", String(20), ForeignKey("stocks.symbol"), nullable=False),
    Column("exit_rule", String(20), nullable=False),
    Column("signal_date", Date, nullable=False),
    Column("entry_date", Date, nullable=False),
    Column("entry_price", Numeric(12, 4), nullable=False),
    Column("exit_date", Date),  # NULL：仍持有中
    Column("exit_price", Numeric(12, 4)),
    Column("exit_reason", String(10)),  # hold：持有期滿 / trailing：移動停利 / max_hold：達最長天數 / ma_break：跌破季線
    Column("dividends", Numeric(12, 4), nullable=False),  # 持有期間領到的每股現金股利
    Column("holding_days", Integer, nullable=False),  # 持有的交易日數（含進場日與出場日）
    Column("net_return", Numeric(12, 6)),  # 扣成本、含股利的報酬率（0.05 = 5%）；持有中為 NULL
    Column("updated_at", DateTime(timezone=True), nullable=False, server_default=func.now()),
    UniqueConstraint("strategy", "symbol", "exit_rule", "entry_date", name="uq_backtest_trades_key"),
)

TRADE_VALUE_COLUMNS = (
    "signal_date", "entry_price", "exit_date", "exit_price", "exit_reason", "dividends", "holding_days", "net_return",
)

# 策略回測統計：scope 為股票代號，或 ALL（觀察名單全部合併）。比率皆為小數（0.55 = 55%）
STAT_VALUE_COLUMNS = (
    "period_start", "period_end", "trades", "open_trades", "wins",
    "win_rate", "win_rate_low", "win_rate_high",
    "avg_return", "median_return", "avg_win", "avg_loss", "payoff_ratio", "profit_factor",
    "max_loss", "max_consecutive_losses", "avg_holding_days",
    "baseline_trades", "baseline_avg_return", "baseline_win_rate", "buy_hold_return",
    # 超額報酬 = 交易報酬 − 同一檔股票任意日進場的平均；
    # has_edge：95% 信賴區間下限 > 0，且前後兩段（以 split_date 切分）的超額報酬都 > 0
    "excess_return", "excess_low", "excess_high",
    "split_date", "trades_first", "excess_first", "trades_second", "excess_second",
    "has_edge",
    # 資金曲線（見 backtest.py）：策略的累積報酬、年化報酬、最大回撤、在場時間比例，以及同期買進持有的年化報酬與最大回撤
    "total_return", "cagr", "max_drawdown", "exposure", "bh_cagr", "bh_max_drawdown",
)
_INTEGER_STATS = {"trades", "open_trades", "wins", "max_consecutive_losses", "baseline_trades"}
_NULLABLE_INTEGER_STATS = {"trades_first", "trades_second"}  # 後來新增的欄位允許 NULL，既有資料表才能自動補上
backtest_stats = Table(
    "backtest_stats",
    metadata,
    Column("id", BigInteger, Identity(), primary_key=True),
    Column("strategy", String(30), nullable=False),
    Column("scope", String(20), nullable=False),
    Column("exit_rule", String(20), nullable=False),
    *(
        Column(name, Date) if name.startswith("period_") or name.endswith("_date")
        else Column(name, Boolean) if name == "has_edge"
        else Column(name, Integer, nullable=False) if name in _INTEGER_STATS
        else Column(name, Integer) if name in _NULLABLE_INTEGER_STATS
        else Column(name, Numeric(12, 6))
        for name in STAT_VALUE_COLUMNS
    ),
    Column("updated_at", DateTime(timezone=True), nullable=False, server_default=func.now()),
    UniqueConstraint("strategy", "scope", "exit_rule", name="uq_backtest_stats_key"),
)


# 參考價位：以 basis_date 當天收盤後的資料計算、供下一個交易日參考。每天新增一筆並保留，
# 日後可回頭檢視當時看到的價位（除權息預告會變動，事後無法重算出一樣的結果）
LEVEL_VALUE_COLUMNS = (
    "close_price", "atr14", "atr_stop",
    "swing_days", "swing_high", "swing_high_date", "swing_low", "swing_low_date", "swing_trend",
    "fib_382", "fib_500", "fib_618",
    "reference_price", "reference_exact", "limit_up", "limit_down",
    "next_ex_date", "next_ex_type", "next_cash_dividend",
)
reference_levels = Table(
    "reference_levels",
    metadata,
    Column("id", BigInteger, Identity(), primary_key=True),
    Column("symbol", String(20), nullable=False),
    Column("basis_date", Date, nullable=False),  # 計算依據的交易日（最新收盤日）
    Column("close_price", Numeric(12, 4), nullable=False),
    Column("atr14", Numeric(12, 4)),
    Column("atr_stop", Numeric(12, 4)),  # 收盤價 − 3 × ATR14（指標暖身期為 NULL）
    Column("swing_days", Integer, nullable=False),  # 波段區間實際的交易日數（資料不足 60 天時較少）
    Column("swing_high", Numeric(12, 4), nullable=False),
    Column("swing_high_date", Date, nullable=False),
    Column("swing_low", Numeric(12, 4), nullable=False),
    Column("swing_low_date", Date, nullable=False),
    Column("swing_trend", String(4), nullable=False),  # up：低點在前（回檔位置）/ down：高點在前（反彈位置）
    Column("fib_382", Numeric(12, 4), nullable=False),
    Column("fib_500", Numeric(12, 4), nullable=False),
    Column("fib_618", Numeric(12, 4), nullable=False),
    Column("reference_price", Numeric(12, 4), nullable=False),  # 下一個交易日的參考價
    Column("reference_exact", Boolean, nullable=False),  # false：下一個交易日除權，參考價以官方公告為準
    Column("limit_up", Numeric(12, 4), nullable=False),
    Column("limit_down", Numeric(12, 4), nullable=False),
    Column("next_ex_date", Date),  # 官方預告中 basis_date 之後最近的除權息日
    Column("next_ex_type", String(4)),  # 權 / 息 / 權息
    Column("next_cash_dividend", Numeric(18, 8)),  # 每股現金股利（未公告為 NULL）
    Column("updated_at", DateTime(timezone=True), nullable=False, server_default=func.now()),
    UniqueConstraint("symbol", "basis_date", name="uq_reference_levels_symbol_basis_date"),
    ForeignKeyConstraint(
        ["symbol", "basis_date"],
        ["stock_prices.symbol", "stock_prices.trade_date"],
        name="fk_reference_levels_stock_prices",
        ondelete="CASCADE",
    ),
)


# 官方開放資料（證交所上市 / 櫃買中心上櫃）的原始日成交資訊：一個市場、一檔證券、一個交易日一筆。
# 保留官方原始數值，不做除權息 / 分割調整；與 yfinance 的 stock_prices 分開存放，日後可據此重新計算
official_daily_quotes = Table(
    "official_daily_quotes",
    metadata,
    Column("id", BigInteger, Identity(), primary_key=True),
    Column("market", String(4), nullable=False),  # TWSE / TPEX
    Column("code", String(10), nullable=False),
    Column("name", String(100), nullable=False),
    Column("trade_date", Date, nullable=False),
    Column("open_price", Numeric(12, 4)),  # 當日無成交時開高低收為 NULL
    Column("high_price", Numeric(12, 4)),
    Column("low_price", Numeric(12, 4)),
    Column("close_price", Numeric(12, 4)),
    # 官方漲跌價差。注意：證交所在除權息日填 0（不是相對除權息參考價的漲跌），
    # 因此不能用「收盤價 − 漲跌價差」推算當日參考價；事件日的偵測見 sql/verify.sql 查詢 17
    Column("change", Numeric(12, 4)),
    Column("next_reference_price", Numeric(12, 4)),  # 官方次一交易日參考價（只有櫃買中心提供）
    Column("volume", BigInteger),  # 成交股數
    Column("trade_value", BigInteger),  # 成交金額（元）
    Column("transactions", BigInteger),  # 成交筆數
    Column("source", String(20), nullable=False),  # 第一次寫入的來源：twse_csv / twse_openapi / tpex_openapi
    Column("created_at", DateTime(timezone=True), nullable=False, server_default=func.now()),
    Column("updated_at", DateTime(timezone=True), nullable=False, server_default=func.now()),
    UniqueConstraint("market", "code", "trade_date", name="uq_official_daily_quotes_market_code_date"),
    Index("ix_official_daily_quotes_date", "trade_date"),
)

OFFICIAL_VALUE_COLUMNS = (
    "name", "open_price", "high_price", "low_price", "close_price", "change",
    "next_reference_price", "volume", "trade_value", "transactions",
)

# 除權除息預告：一個市場、一檔證券、一個除權息日一筆。預告內容可能在除權息日前修正，以最新一次為準。
# 日後區分「分割 / 配股」與「現金股利」時需要現金股利金額；開放資料只提供當下的預告，過了就查不到
official_corporate_actions = Table(
    "official_corporate_actions",
    metadata,
    Column("id", BigInteger, Identity(), primary_key=True),
    Column("market", String(4), nullable=False),  # TWSE / TPEX
    Column("code", String(10), nullable=False),
    Column("name", String(100), nullable=False),
    Column("ex_date", Date, nullable=False),  # 除權息交易日
    Column("action_type", String(4), nullable=False),  # 權 / 息 / 權息
    Column("cash_dividend", Numeric(18, 8)),  # 每股現金股利（元）
    Column("stock_dividend_ratio", Numeric(18, 8)),  # 官方「無償配股率」欄位原值
    Column("raw", JSONB, nullable=False),  # 官方原始欄位（全部保留，欄位名稱依來源而異）
    Column("source", String(20), nullable=False),  # 第一次寫入的來源：twse_actions / tpex_actions
    Column("created_at", DateTime(timezone=True), nullable=False, server_default=func.now()),
    Column("updated_at", DateTime(timezone=True), nullable=False, server_default=func.now()),
    UniqueConstraint("market", "code", "ex_date", name="uq_official_corporate_actions_market_code_date"),
)

CORPORATE_ACTION_VALUE_COLUMNS = ("name", "action_type", "cash_dividend", "stock_dividend_ratio", "raw")


class SchemaMismatchError(RuntimeError):
    """資料庫中已存在結構不同的同名資料表。"""


def init_schema(engine: Engine) -> list[str]:
    """建立資料表（已存在則略過），並補上新版程式新增的欄位，回傳補上的欄位（「資料表.欄位」）。

    新增的欄位若允許 NULL，直接 ALTER TABLE ADD COLUMN，既有資料該欄為 NULL；
    不允許 NULL 的欄位無法自動補上（既有資料沒有值），視為結構不一致。
    """
    metadata.create_all(engine)

    inspector = inspect(engine)
    added = []
    with engine.begin() as conn:
        for table in metadata.sorted_tables:
            actual = {col["name"] for col in inspector.get_columns(table.name)}
            missing = [col for col in table.columns if col.name not in actual]
            if required := sorted(col.name for col in missing if not col.nullable):
                raise SchemaMismatchError(
                    f"資料表 {table.name} 已存在但缺少必填欄位 {required}，"
                    "可能是舊版程式建立的；確認資料可捨棄後請先 DROP 該表再重新執行"
                )
            for col in missing:
                col_type = col.type.compile(dialect=engine.dialect)
                conn.execute(text(f'ALTER TABLE {table.name} ADD COLUMN IF NOT EXISTS {col.name} {col_type}'))
                added.append(f"{table.name}.{col.name}")
    return added


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


def upsert_actions(conn: Connection, rows: list[dict]) -> dict[str, int]:
    """寫入除權息與股本事件，回傳新增 / 更新 / 未變動筆數（Yahoo 重新調整金額時會更新）。"""
    return _upsert(conn, stock_actions, rows, "uq_stock_actions_symbol_ex_date", ACTION_COLUMNS)


def get_history_from(conn: Connection, symbol: str):
    """資料庫中這檔股票完整歷史的起始設定；尚未補齊（或股票不存在）時為 None。"""
    return conn.execute(select(stocks.c.history_from).where(stocks.c.symbol == symbol)).scalar()


def mark_history_complete(conn: Connection, symbol: str, history_from) -> None:
    conn.execute(stocks.update().where(stocks.c.symbol == symbol).values(history_from=history_from))


def overlap_start(conn: Connection, symbol: str, days: int):
    """資料庫中這檔股票倒數第 days 個交易日（增量抓取的起始日）；資料不足時為最早的一天，沒有資料時為 None。"""
    recent = (
        select(stock_prices.c.trade_date)
        .where(stock_prices.c.symbol == symbol)
        .order_by(stock_prices.c.trade_date.desc())
        .limit(days)
        .subquery()
    )
    return conn.execute(select(func.min(recent.c.trade_date))).scalar()


def load_closes(conn: Connection, symbol: str, dates) -> dict:
    """指定日期在資料庫中的收盤價（日期 → Decimal），用來偵測 Yahoo 是否重新調整了歷史價格。"""
    stmt = select(stock_prices.c.trade_date, stock_prices.c.close_price).where(
        stock_prices.c.symbol == symbol, stock_prices.c.trade_date.in_(list(dates))
    )
    return {row.trade_date: row.close_price for row in conn.execute(stmt)}


def upsert_official_quotes(conn: Connection, rows: list[dict]) -> dict[str, int]:
    """寫入官方日成交資訊，回傳新增 / 更新 / 未變動筆數。

    source 不列入比較：證交所 CSV 與 OpenAPI 的同一天資料內容相同，不會因來源不同而反覆更新。
    """
    return _upsert(conn, official_daily_quotes, rows, "uq_official_daily_quotes_market_code_date", OFFICIAL_VALUE_COLUMNS)


def upsert_corporate_actions(conn: Connection, rows: list[dict]) -> dict[str, int]:
    """寫入除權除息預告，回傳新增 / 更新 / 未變動筆數（預告內容修正時會更新）。"""
    return _upsert(
        conn, official_corporate_actions, rows,
        "uq_official_corporate_actions_market_code_date", CORPORATE_ACTION_VALUE_COLUMNS,
    )


def database_size_bytes(conn: Connection) -> int:
    """目前資料庫大小（PostgreSQL 統計值，與 Neon 計費用量可能略有差異）。"""
    return conn.execute(text("SELECT pg_database_size(current_database())")).scalar()


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


def load_backtest_inputs(conn: Connection, symbol: str) -> tuple[pd.DataFrame, dict, dict]:
    """回測輸入：（日 K + ATR / MA60 / MACD 訊號線, 策略 → 訊號日期集合, 除息日 → 現金股利）。"""
    bars = select(
        stock_prices.c.trade_date, stock_prices.c.open_price, stock_prices.c.close_price,
        stock_indicators.c.atr14, stock_indicators.c.ma60, stock_indicators.c.macd_signal,
    ).join(
        stock_indicators,
        (stock_indicators.c.symbol == stock_prices.c.symbol) & (stock_indicators.c.trade_date == stock_prices.c.trade_date),
    ).where(stock_prices.c.symbol == symbol).order_by(stock_prices.c.trade_date)
    frame = pd.DataFrame(conn.execute(bars).mappings().all())

    signals = {}
    for row in conn.execute(select(strategy_signals.c.strategy, strategy_signals.c.trade_date).where(strategy_signals.c.symbol == symbol)):
        signals.setdefault(row.strategy, set()).add(row.trade_date)

    dividends = {
        row.ex_date: row.cash_dividend
        for row in conn.execute(
            select(stock_actions.c.ex_date, stock_actions.c.cash_dividend)
            .where(stock_actions.c.symbol == symbol, stock_actions.c.cash_dividend.is_not(None))
        )
    }
    return frame, signals, dividends


def sync_backtest_trades(conn: Connection, symbol: str, rows: list[dict]) -> dict[str, int]:
    """讓資料庫中這檔股票的回測交易與本次計算一致：寫入新增 / 變動的，刪除不再出現的。"""
    counts = _upsert(conn, backtest_trades, rows, "uq_backtest_trades_key", TRADE_VALUE_COLUMNS)
    keep = {(r["strategy"], r["exit_rule"], r["entry_date"]) for r in rows}
    existing = conn.execute(
        select(backtest_trades.c.id, backtest_trades.c.strategy, backtest_trades.c.exit_rule, backtest_trades.c.entry_date)
        .where(backtest_trades.c.symbol == symbol)
    ).all()
    stale = [row.id for row in existing if (row.strategy, row.exit_rule, row.entry_date) not in keep]
    if stale:
        conn.execute(backtest_trades.delete().where(backtest_trades.c.id.in_(stale)))
    return {**counts, "deleted": len(stale)}


def sync_backtest_stats(conn: Connection, rows: list[dict]) -> dict[str, int]:
    """寫入回測統計，並刪除不再出現的組合（例如移除了某個策略或股票）。"""
    counts = _upsert(conn, backtest_stats, rows, "uq_backtest_stats_key", STAT_VALUE_COLUMNS)
    keep = {(r["strategy"], r["scope"], r["exit_rule"]) for r in rows}
    existing = conn.execute(select(backtest_stats.c.id, backtest_stats.c.strategy, backtest_stats.c.scope, backtest_stats.c.exit_rule)).all()
    stale = [row.id for row in existing if (row.strategy, row.scope, row.exit_rule) not in keep]
    if stale:
        conn.execute(backtest_stats.delete().where(backtest_stats.c.id.in_(stale)))
    return {**counts, "deleted": len(stale)}


def load_level_inputs(conn: Connection, symbol: str, days: int) -> pd.DataFrame:
    """參考價位的輸入：最近 days 個交易日的高低收與 ATR14（依日期由舊到新，數值為 Decimal）。"""
    stmt = (
        select(
            stock_prices.c.trade_date, stock_prices.c.high_price, stock_prices.c.low_price,
            stock_prices.c.close_price, stock_indicators.c.atr14,
        )
        .outerjoin(
            stock_indicators,
            (stock_indicators.c.symbol == stock_prices.c.symbol)
            & (stock_indicators.c.trade_date == stock_prices.c.trade_date),
        )
        .where(stock_prices.c.symbol == symbol)
        .order_by(stock_prices.c.trade_date.desc())
        .limit(days)
    )
    rows = conn.execute(stmt).mappings().all()
    return pd.DataFrame(list(reversed(rows)), columns=["trade_date", "high_price", "low_price", "close_price", "atr14"])


def load_next_corporate_action(conn: Connection, market: str, code: str, after) -> dict | None:
    """官方預告中 after 之後最近的一筆除權息（ex_date、action_type、cash_dividend），沒有則 None。"""
    t = official_corporate_actions
    row = conn.execute(
        select(t.c.ex_date, t.c.action_type, t.c.cash_dividend)
        .where(t.c.market == market, t.c.code == code, t.c.ex_date > after)
        .order_by(t.c.ex_date)
        .limit(1)
    ).mappings().first()
    return dict(row) if row else None


def upsert_reference_levels(conn: Connection, rows: list[dict]) -> dict[str, int]:
    if not rows:
        return {"inserted": 0, "updated": 0, "unchanged": 0}
    return _upsert(conn, reference_levels, rows, "uq_reference_levels_symbol_basis_date", LEVEL_VALUE_COLUMNS)


def _upsert(
    conn: Connection, table: Table, rows: list[dict], constraint: str, value_columns: tuple[str, ...], chunk_size: int = 1000
) -> dict[str, int]:
    """依唯一鍵（constraint）寫入，回傳新增 / 更新 / 未變動筆數。

    - 已存在 → 只有數值不同時才更新
    - 數值完全相同 → 不動，重複執行不會產生重複資料
    - 分批寫入，避免單一 SQL 參數過多（PostgreSQL 上限 65,535 個；例如十年日 K 的指標約 3 萬個參數）
    """
    totals = {"inserted": 0, "updated": 0, "unchanged": 0}
    for start in range(0, len(rows), chunk_size):
        chunk = rows[start:start + chunk_size]
        stmt = pg_insert(table).values(chunk)
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
        totals["inserted"] += sum(flags)
        totals["updated"] += len(flags) - sum(flags)
        totals["unchanged"] += len(chunk) - len(flags)
    return totals
