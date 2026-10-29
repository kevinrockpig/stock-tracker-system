"""技術指標計算：MA、MACD、RSI 使用 TA-Lib，KD 依台股慣例自行實作。

輸入為資料庫讀出、只含交易日的日 K（依日期由舊到新），因此 MA20 代表 20 個「交易日」，
週末、國定假日與颱風假不會被算進去。輸出筆數與日期和輸入完全相同。
"""

from decimal import Decimal

import numpy as np
import pandas as pd
import talib

MA_PERIODS = (5, 10, 20, 60)
MACD_FAST, MACD_SLOW, MACD_SIGNAL = 12, 26, 9
RSI_PERIODS = (6, 12)
KD_PERIOD = 9

# MACD、RSI、KD 是遞迴平滑（EMA / Wilder 平滑 / 1/3 平滑），起始值會影響前段數值，
# 需要一段資料暖身才會與券商軟體一致。前 WARMUP_BARS 筆不輸出這三種指標（MA 不受影響）。
WARMUP_BARS = 120
RECURSIVE_COLUMNS = ("macd_dif", "macd_signal", "macd_osc", "rsi6", "rsi12", "kd_k", "kd_d")


def compute_indicators(prices: pd.DataFrame) -> pd.DataFrame:
    """prices 需有 trade_date, high_price, low_price, close_price 欄位，依日期由舊到新。"""
    high = prices["high_price"].to_numpy(dtype=float)
    low = prices["low_price"].to_numpy(dtype=float)
    close = prices["close_price"].to_numpy(dtype=float)

    result = pd.DataFrame({"trade_date": prices["trade_date"].to_numpy()})
    for period in MA_PERIODS:
        result[f"ma{period}"] = talib.SMA(close, timeperiod=period)

    # 台股名稱：DIF = EMA12 − EMA26、MACD = DIF 的 9 日 EMA（訊號線）、OSC = DIF − MACD（柱狀體）
    dif, signal, osc = talib.MACD(close, fastperiod=MACD_FAST, slowperiod=MACD_SLOW, signalperiod=MACD_SIGNAL)
    result["macd_dif"], result["macd_signal"], result["macd_osc"] = dif, signal, osc

    for period in RSI_PERIODS:
        result[f"rsi{period}"] = talib.RSI(close, timeperiod=period)

    result["kd_k"], result["kd_d"] = taiwan_kd(high, low, close, KD_PERIOD)

    result.loc[: WARMUP_BARS - 1, list(RECURSIVE_COLUMNS)] = np.nan
    return result


def taiwan_kd(high: np.ndarray, low: np.ndarray, close: np.ndarray, period: int = 9) -> tuple[np.ndarray, np.ndarray]:
    """台股 KD（9,3,3）。TA-Lib 的 STOCH 用簡單平均平滑，數值與台股軟體不同，因此自行實作：

    RSV = (今日收盤 − 近 N 日最低) / (近 N 日最高 − 近 N 日最低) × 100
    K = 2/3 × 前一日 K + 1/3 × RSV
    D = 2/3 × 前一日 D + 1/3 × K
    K、D 起始值為 50；近 N 日最高等於最低（價格完全沒變動）時，RSV 以 50 計。
    """
    k = np.full(len(close), np.nan)
    d = np.full(len(close), np.nan)
    prev_k = prev_d = 50.0
    for i in range(period - 1, len(close)):
        highest = high[i - period + 1 : i + 1].max()
        lowest = low[i - period + 1 : i + 1].min()
        rsv = 50.0 if highest == lowest else (close[i] - lowest) / (highest - lowest) * 100
        prev_k = prev_k * 2 / 3 + rsv / 3
        prev_d = prev_d * 2 / 3 + prev_k / 3
        k[i], d[i] = prev_k, prev_d
    return k, d


def to_indicator_records(symbol: str, indicators: pd.DataFrame) -> list[dict]:
    """轉為 SQLAlchemy 可直接寫入的 dict 清單（四捨五入到小數 4 位，NaN 轉為 NULL）。"""
    value_columns = [c for c in indicators.columns if c != "trade_date"]
    records = []
    for row in indicators.itertuples(index=False):
        record = {"symbol": symbol, "trade_date": row.trade_date}
        for column in value_columns:
            value = getattr(row, column)
            record[column] = None if pd.isna(value) else Decimal(str(round(float(value), 4)))
        records.append(record)
    return records
