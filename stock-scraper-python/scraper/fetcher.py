"""使用 yfinance 擷取日行情，並以 Pandas 清理成可寫入資料庫的格式。"""

import logging
import statistics
import warnings
from dataclasses import dataclass
from datetime import date
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


@dataclass
class FetchResult:
    prices: pd.DataFrame  # 欄位：trade_date, open_price, high_price, low_price, close_price, volume
    currency: str | None
    actions: pd.DataFrame  # 欄位：ex_date, cash_dividend, split_ratio（只含有事件的日期）


def fetch_daily_prices(symbol: str, start: date) -> FetchResult:
    """擷取 start（含）之後的日 K 與除權息事件。"""
    ticker = yf.Ticker(symbol)
    # auto_adjust=False：不還原除息，價格與券商報價一致。
    # 配股、減資、分割則由 Yahoo 調整：事件之前的價格與股利都等比例換算，前後可連續比較（例如 0050 於 2025 年 6 月 1 拆 4）
    raw = ticker.history(start=start.isoformat(), interval="1d", auto_adjust=False, actions=True)
    if raw.empty:
        raise FetchError(f"{symbol} 沒有取得任何資料（代號錯誤、已下市，或 Yahoo 暫時阻擋請求）")

    meta = ticker.history_metadata or {}
    df = _clean(symbol, raw)
    df = _drop_unfinished_bar(symbol, df, meta)
    if df.empty:
        raise FetchError(f"{symbol} 清理後沒有可用的收盤資料")
    return FetchResult(df, meta.get("currency"), extract_actions(raw))


def extract_actions(raw: pd.DataFrame) -> pd.DataFrame:
    """取出有現金股利或股數變動的日期（在清理之前取，避免除權息日剛好是被略過的資料）。"""
    dividends = raw.get("Dividends", pd.Series(0.0, index=raw.index)).fillna(0)
    splits = raw.get("Stock Splits", pd.Series(0.0, index=raw.index)).fillna(0)
    has_event = (dividends != 0) | (splits != 0)
    return pd.DataFrame({
        "ex_date": raw.index[has_event].date,
        "cash_dividend": dividends[has_event].round(6).where(lambda v: v != 0).to_numpy(),
        "split_ratio": splits[has_event].round(6).where(lambda v: v != 0).to_numpy(),
    })


# Yahoo 在配股、減資、分割後會把「之前所有」價格等比例重算；增量抓取時，資料庫中更早的資料不會跟著更新，
# 價格會在事件前後接不起來。比對本次抓到的最舊幾天（與資料庫重疊的日期）的收盤價，整體差超過容許值就代表需要重抓完整歷史。
READJUST_CHECK_DAYS = 5
READJUST_TOLERANCE = 0.001  # 0.1%：遠大於四捨五入誤差，遠小於任何配股或減資比例


def detect_readjustment(fetched: pd.DataFrame, stored_closes: dict) -> str | None:
    """fetched：本次抓到的日 K（由舊到新）；stored_closes：同日期在資料庫中的收盤價。有重新調整時回傳說明。"""
    pairs = [
        (row.trade_date, float(stored_closes[row.trade_date]), float(row.close_price))
        for row in fetched.head(READJUST_CHECK_DAYS).itertuples()
        if row.trade_date in stored_closes and stored_closes[row.trade_date]
    ]
    if not pairs:
        return None
    ratio = statistics.median(new / old for _, old, new in pairs)
    if abs(ratio - 1) <= READJUST_TOLERANCE:
        return None
    day, old, new = pairs[0]
    return f"Yahoo 已重新調整歷史價格（{day} 收盤 資料庫 {old:g} → 現在 {new:g}，比例 {ratio:.4f}，可能是配股、減資或分割）"


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


def to_action_records(symbol: str, actions: pd.DataFrame) -> list[dict]:
    def decimal_or_none(value):
        return None if pd.isna(value) else Decimal(str(value))

    return [
        {"symbol": symbol, "ex_date": row.ex_date,
         "cash_dividend": decimal_or_none(row.cash_dividend), "split_ratio": decimal_or_none(row.split_ratio)}
        for row in actions.itertuples(index=False)
    ]


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
