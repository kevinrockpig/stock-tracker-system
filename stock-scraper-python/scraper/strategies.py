"""技術策略掃描：依資料庫中的收盤價與技術指標，判斷每個交易日符合哪些策略。

輸入只含交易日（依日期由舊到新），因此「前一日」指前一個交易日，週末、國定假日與颱風假不算在內。
指標為 NULL（暖身期或資料不足）的日子一律視為不符合。
"""

import pandas as pd

# 策略代碼 → 中文名稱（代碼同時用於資料庫與 API 查詢參數）
STRATEGIES = {
    "ma_bullish": "均線多頭排列",
    "golden_cross": "MACD 黃金交叉",
    "break_ma20": "突破 20 日均線",
}

# 狀態型策略：條件成立期間每天都符合（例如多頭排列可連續數十天）；回測只把「進入狀態的第一天」當作進場訊號。
# 其餘為事件型（交叉、突破），只在發生當天成立
STATE_STRATEGIES = {"ma_bullish"}

INPUT_COLUMNS = ("close_price", "ma5", "ma10", "ma20", "ma60", "macd_dif", "macd_signal")


def evaluate_strategies(data: pd.DataFrame) -> pd.DataFrame:
    """data 需有 trade_date 與 INPUT_COLUMNS 欄位。回傳 trade_date + 每個策略一個布林欄位。"""
    values = data[list(INPUT_COLUMNS)].astype(float)  # Decimal / None → float / NaN
    close, ma5, ma10, ma20, ma60 = (values[c] for c in ("close_price", "ma5", "ma10", "ma20", "ma60"))
    dif, signal = values["macd_dif"], values["macd_signal"]

    result = pd.DataFrame({"trade_date": data["trade_date"].to_numpy()})
    # 均線多頭排列：短天期均線依序在長天期之上
    result["ma_bullish"] = (ma5 > ma10) & (ma10 > ma20) & (ma20 > ma60)
    # MACD 黃金交叉：DIF 由下往上穿越 MACD（訊號線），只在交叉當天成立
    result["golden_cross"] = (dif > signal) & (dif.shift(1) <= signal.shift(1))
    # 突破 20 日均線：收盤價由下往上穿越 MA20，只在突破當天成立
    result["break_ma20"] = (close > ma20) & (close.shift(1) <= ma20.shift(1))
    # 與 NaN 比較的結果皆為 False，因此資料不足的日子不會被誤判為符合
    return result


def to_signal_set(result: pd.DataFrame) -> set[tuple]:
    """轉為 {(trade_date, strategy)}，只保留符合的組合。"""
    return {
        (row.trade_date, strategy)
        for strategy in STRATEGIES
        for row in result.loc[result[strategy], ["trade_date"]].itertuples(index=False)
    }
