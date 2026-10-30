"""參考價位：每日收盤後，依最新一個交易日的資料，計算下一個交易日可參考的價位（公式計算，不預測漲跌）。

執行：python -m scraper.levels（每日由 scraper.main 在回測之後自動執行）

  ATR 停損   收盤價 − 3 × ATR(14)。與回測「移動停利」進場當天的停利線相同；之後收盤創新高時，
             停利線跟著上移（進場後最高收盤價 − 3 × ATR）
  黃金分割   最近 60 個交易日（含當天）的最高價與最低價。最低價在前、最高價在後（上漲波段）時，
             回檔位置為 最高 − 比例 × 區間；最高價在前（下跌波段）時，反彈位置為 最低 + 比例 × 區間
  漲跌停     下一個交易日的參考價 ±10%，漲停價向下、跌停價向上取到有效的升降單位
  除權息     官方預告（official_corporate_actions，由 scraper.official 每日累積）中，最新交易日之後最近的一筆

下一個交易日的參考價：
  - 平常為最新收盤價
  - 預告的除息日就是下一個交易日、且只除息（不含除權）、已公告現金股利時，參考價 = 收盤價 − 現金股利
  - 除權（配股、現金增資）的參考價還需要認購價等資料，不自行計算：仍以收盤價計算並標示「不精確」

已知限制：
  - 「下一個交易日」以下一個週一至週五判斷，無法得知國定假日、颱風假（除權息日一定是交易日，
    所以預告的除權息日若不是下一個週間日，就不會被當成下一個交易日，只是提醒）
  - 證交所除息參考價的小數處理以官方公告為準，這裡不做進位；國外成分 ETF 沒有漲跌幅限制，這裡仍以 10% 計算
"""

import argparse
import logging
import sys
from datetime import date, timedelta
from decimal import ROUND_CEILING, ROUND_FLOOR, Decimal

import pandas as pd
from sqlalchemy import create_engine

from scraper.backtest import TRAILING_ATR_MULTIPLE, is_etf
from scraper.config import WATCHLIST, ConfigError, build_database_url
from scraper.db import init_schema, load_level_inputs, load_next_corporate_action, upsert_reference_levels

logger = logging.getLogger("scraper.levels")

SWING_DAYS = 60
FIB_RATIOS = {"fib_382": Decimal("0.382"), "fib_500": Decimal("0.5"), "fib_618": Decimal("0.618")}
PRICE_LIMIT = Decimal("0.10")

# 升降單位（證交所、櫃買中心相同）：(價格未滿, 單位)
STOCK_TICKS = (
    (Decimal(10), Decimal("0.01")),
    (Decimal(50), Decimal("0.05")),
    (Decimal(100), Decimal("0.1")),
    (Decimal(500), Decimal("0.5")),
    (Decimal(1000), Decimal(1)),
    (None, Decimal(5)),
)
ETF_TICKS = ((Decimal(50), Decimal("0.01")), (None, Decimal("0.05")))

# 回傳值的小數位數（與資料表 Numeric(12, 4) 一致）
_FOUR = Decimal("0.0001")


def tick_size(price: Decimal, etf: bool) -> Decimal:
    for upper, tick in ETF_TICKS if etf else STOCK_TICKS:
        if upper is None or price < upper:
            return tick
    raise AssertionError("unreachable")


def _to_tick(raw: Decimal, etf: bool, rounding: str) -> Decimal:
    tick = tick_size(raw, etf)
    return (raw / tick).to_integral_value(rounding=rounding) * tick


def limit_prices(reference: Decimal, etf: bool) -> tuple[Decimal, Decimal]:
    """（漲停價, 跌停價）：參考價 ±10%，漲停向下、跌停向上取到該價位的升降單位，不超出 10%。"""
    up = _to_tick(reference * (1 + PRICE_LIMIT), etf, ROUND_FLOOR)
    down = _to_tick(reference * (1 - PRICE_LIMIT), etf, ROUND_CEILING)
    return up, down


def next_weekday(day: date) -> date:
    day += timedelta(days=1)
    while day.weekday() >= 5:
        day += timedelta(days=1)
    return day


def next_reference(close: Decimal, basis_date: date, action: dict | None) -> tuple[Decimal, bool]:
    """下一個交易日的參考價與是否精確（見模組說明）。"""
    if not action or action["ex_date"] != next_weekday(basis_date):
        return close, True
    cash = action["cash_dividend"]
    if action["action_type"] == "息" and cash is not None:
        return close - cash, True
    return close, False  # 除權，或尚未公告股利金額


def swing_levels(bars: pd.DataFrame) -> dict:
    """最近 SWING_DAYS 個交易日的波段高低點與黃金分割位置。bars 依日期由舊到新。"""
    window = bars.tail(SWING_DAYS).to_dict("records")  # 價格為 Decimal，用 Python 比較，不經過 pandas 的數值運算
    high_bar = max(window, key=lambda bar: bar["high_price"])  # 同價時取最早的一天
    low_bar = min(window, key=lambda bar: bar["low_price"])
    high, low = high_bar["high_price"], low_bar["low_price"]
    high_date, low_date = high_bar["trade_date"], low_bar["trade_date"]
    trend = "up" if low_date < high_date else "down"
    span = high - low
    levels = {
        key: (high - ratio * span if trend == "up" else low + ratio * span).quantize(_FOUR)
        for key, ratio in FIB_RATIOS.items()
    }
    return {
        "swing_days": len(window),
        "swing_high": high,
        "swing_high_date": high_date,
        "swing_low": low,
        "swing_low_date": low_date,
        "swing_trend": trend,
        **levels,
    }


def compute_levels(symbol: str, bars: pd.DataFrame, action: dict | None) -> dict:
    """bars：最近的日 K（trade_date、high_price、low_price、close_price、atr14，Decimal，由舊到新）。"""
    last = bars.iloc[-1]
    basis_date, close, atr = last["trade_date"], last["close_price"], last["atr14"]
    etf = is_etf(symbol)
    reference, exact = next_reference(close, basis_date, action)
    limit_up, limit_down = limit_prices(reference, etf)
    has_atr = atr is not None and not pd.isna(atr)
    return {
        "symbol": symbol,
        "basis_date": basis_date,
        "close_price": close,
        "atr14": atr if has_atr else None,
        "atr_stop": (close - Decimal(str(TRAILING_ATR_MULTIPLE)) * atr).quantize(_FOUR) if has_atr else None,
        **swing_levels(bars),
        "reference_price": reference,
        "reference_exact": exact,
        "limit_up": limit_up,
        "limit_down": limit_down,
        "next_ex_date": action["ex_date"] if action else None,
        "next_ex_type": action["action_type"] if action else None,
        "next_cash_dividend": action["cash_dividend"] if action else None,
    }


def market_code(symbol: str) -> tuple[str, str]:
    """yfinance 代號 → （官方市場, 證券代號）：2330.TW → (TWSE, 2330)、6488.TWO → (TPEX, 6488)。"""
    code, _, suffix = symbol.partition(".")
    return ("TPEX" if suffix.upper() == "TWO" else "TWSE"), code


def run_reference_levels(engine, symbols) -> list[dict]:
    """計算並寫入 symbols 的最新參考價位，回傳寫入的資料（供 log 摘要）。"""
    records = []
    with engine.begin() as conn:
        for symbol in symbols:
            bars = load_level_inputs(conn, symbol, SWING_DAYS)
            if bars.empty:
                logger.warning("[%s] 沒有日 K，略過參考價位", symbol)
                continue
            market, code = market_code(symbol)
            action = load_next_corporate_action(conn, market, code, after=bars["trade_date"].iloc[-1])
            records.append(compute_levels(symbol, bars, action))
        counts = upsert_reference_levels(conn, records)
    logger.info("參考價位 %d 檔：新增 %d / 更新 %d / 未變動 %d", len(records), counts["inserted"], counts["updated"], counts["unchanged"])
    return records


def _fmt(value) -> str:
    return "—" if value is None else f"{value:,.2f}"


def log_levels(records: list[dict]) -> None:
    for r in records:
        exact = "" if r["reference_exact"] else "（除權，參考價以官方公告為準）"
        ex = ""
        if r["next_ex_date"]:
            cash = f" 現金股利 {r['next_cash_dividend']:g}" if r["next_cash_dividend"] is not None else "（股利未公告）"
            ex = f"；除{r['next_ex_type']}日 {r['next_ex_date']}{cash}"
        logger.info(
            "[%s] %s 收盤 %s：ATR 停損 %s｜%d 日波段 %s %s ~ %s，黃金分割 %s / %s / %s｜下一交易日參考價 %s%s 漲停 %s 跌停 %s%s",
            r["symbol"], r["basis_date"], _fmt(r["close_price"]), _fmt(r["atr_stop"]),
            r["swing_days"], "上漲" if r["swing_trend"] == "up" else "下跌", _fmt(r["swing_low"]), _fmt(r["swing_high"]),
            _fmt(r["fib_382"]), _fmt(r["fib_500"]), _fmt(r["fib_618"]),
            _fmt(r["reference_price"]), exact, _fmt(r["limit_up"]), _fmt(r["limit_down"]), ex,
        )


def main() -> None:
    argparse.ArgumentParser(description="依資料庫中的日 K 與除權息預告計算參考價位").parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-7s %(message)s", datefmt="%H:%M:%S")
    try:
        engine = create_engine(build_database_url(), pool_pre_ping=True, connect_args={"connect_timeout": 15})
        init_schema(engine)
        log_levels(run_reference_levels(engine, WATCHLIST))
    except ConfigError as exc:
        logger.error("設定錯誤：%s", exc)
        sys.exit(2)
    engine.dispose()


if __name__ == "__main__":
    main()
