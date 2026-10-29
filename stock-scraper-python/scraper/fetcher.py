"""使用 yfinance 擷取日行情，並以 Pandas 清理成可寫入資料庫的格式。"""

import logging
import warnings
from decimal import Decimal

import pandas as pd
import yfinance as yf

logger = logging.getLogger(__name__)

# yfinance 內部使用了 pandas 即將移除的參數，警告與本專案無關
warnings.filterwarnings("ignore", category=DeprecationWarning, module="yfinance")

_RENAME = {
    "Open": "open_price",
    "High": "high_price",
    "Low": "low_price",
    "Close": "close_price",
    "Volume": "volume",
}
_PRICE_FIELDS = ("open_price", "high_price", "low_price", "close_price")


class FetchError(RuntimeError):
    """yfinance 無法取得可用資料。"""


def fetch_daily_prices(symbol: str, period: str) -> tuple[pd.DataFrame, str | None]:
    """擷取日 K，回傳 (清理後的 DataFrame, 幣別)。

    DataFrame 欄位：trade_date, open_price, high_price, low_price, close_price, volume
    """
    ticker = yf.Ticker(symbol)
    # auto_adjust=False：不還原除息，價格與券商報價一致（股票分割仍由 Yahoo 調整，分割前後價格可連續比較）
    raw = ticker.history(period=period, interval="1d", auto_adjust=False, actions=False)
    if raw.empty:
        raise FetchError(f"{symbol} 沒有取得任何資料（代號錯誤、已下市，或 Yahoo 暫時阻擋請求）")

    meta = ticker.history_metadata or {}
    df = _clean(symbol, raw)
    df = _drop_unfinished_bar(symbol, df, meta)
    if df.empty:
        raise FetchError(f"{symbol} 清理後沒有可用的收盤資料")
    return df, meta.get("currency")


def _clean(symbol: str, raw: pd.DataFrame) -> pd.DataFrame:
    df = raw.rename(columns=_RENAME)[list(_RENAME.values())].copy()
    # index 已是交易所時區（例如 Asia/Taipei），直接取日期即為當地交易日
    df.insert(0, "trade_date", raw.index.date)

    before = len(df)
    df = df.dropna(subset=["close_price"])
    if dropped := before - len(df):
        logger.warning("%s 移除 %d 筆沒有收盤價的資料", symbol, dropped)

    df = df.drop_duplicates(subset="trade_date", keep="last")
    df[list(_PRICE_FIELDS)] = df[list(_PRICE_FIELDS)].round(4)

    # 休市日 yfinance 有時會補一根「開高低收 = 前一日收盤、成交量 0」的假 K 線，
    # 會讓 K 線圖出現假的一字線，也會讓「前日收盤」取錯日期（db.delete_placeholder_rows 使用相同條件）
    placeholder = (
        (df["volume"] == 0)
        & (df["open_price"] == df["close_price"])
        & (df["high_price"] == df["close_price"])
        & (df["low_price"] == df["close_price"])
    )
    if placeholder.any():
        dates = ", ".join(str(d) for d in df.loc[placeholder, "trade_date"])
        logger.info("%s 略過 %d 筆休市日補值資料（成交量 0 且開高低收相同）：%s", symbol, placeholder.sum(), dates)
        df = df[~placeholder]

    # 開高低收邏輯檢查：最高價不可低於開盤 / 收盤，最低價不可高於開盤 / 收盤
    invalid = (
        (df["high_price"] < df[["open_price", "close_price"]].max(axis=1))
        | (df["low_price"] > df[["open_price", "close_price"]].min(axis=1))
    )
    if invalid.any():
        dates = ", ".join(str(d) for d in df.loc[invalid, "trade_date"])
        logger.warning("%s 移除 %d 筆開高低收不合理的資料：%s", symbol, invalid.sum(), dates)
        df = df[~invalid]

    return df.reset_index(drop=True)


def _drop_unfinished_bar(symbol: str, df: pd.DataFrame, meta: dict) -> pd.DataFrame:
    """盤中執行時，yfinance 會把「今天尚未收盤」的即時數字當成一根日 K 回傳。

    這筆不是收盤價，不寫入資料庫，避免把盤中價誤標為日收盤價。
    """
    end = meta.get("currentTradingPeriod", {}).get("regular", {}).get("end")
    if end is None:
        logger.warning("%s 無法取得交易時段資訊，無法判斷最後一筆是否已收盤", symbol)
        return df

    end = pd.Timestamp(end, unit="s", tz="UTC") if isinstance(end, (int, float)) else pd.Timestamp(end)
    exchange_tz = meta.get("exchangeTimezoneName") or "UTC"
    end = end.tz_convert(exchange_tz) if end.tzinfo else end.tz_localize(exchange_tz)

    last_date = df["trade_date"].iloc[-1]
    if pd.Timestamp.now(tz=exchange_tz) < end and last_date == end.date():
        logger.info("%s 尚未收盤（收盤時間 %s），略過 %s 的盤中資料", symbol, end.strftime("%H:%M %Z"), last_date)
        return df.iloc[:-1]
    return df


def to_records(symbol: str, df: pd.DataFrame) -> list[dict]:
    """轉為 SQLAlchemy 可直接寫入的 dict 清單（價格用 Decimal 對應 NUMERIC）。"""
    records = []
    for row in df.itertuples(index=False):
        record = {"symbol": symbol, "trade_date": row.trade_date}
        for field in _PRICE_FIELDS:
            value = getattr(row, field)
            record[field] = None if pd.isna(value) else Decimal(str(value))
        record["volume"] = None if pd.isna(row.volume) else int(row.volume)
        records.append(record)
    return records
