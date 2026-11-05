"""研究用的本機資料快取：從資料庫唯讀讀出觀察名單的完整日 K、指標、除權息與策略訊號，存成本機檔案。

之後反覆回測只讀這個檔案，不再連線資料庫（Neon 免費方案每月網路傳輸 5 GB，一次完整讀取約 20 MB）。
快取不進 Git（.gitignore 的 research/cache/），內容可隨時重建：

    python -m research.cache            # 建立 / 更新快取
    python -m research.cache --check    # 只顯示快取的內容摘要
"""

import argparse
import logging
import pickle
import sys
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

import pandas as pd
from sqlalchemy import create_engine, text

from scraper.config import WATCHLIST, ConfigError, build_database_url
from scraper.db import INDICATOR_COLUMNS

logger = logging.getLogger(__name__)

CACHE_PATH = Path(__file__).resolve().parent / "cache" / "market.pkl"
CACHE_VERSION = 1


@dataclass
class MarketData:
    """研究用的資料：每檔股票一個 DataFrame（日 K＋指標，依日期由舊到新）與相關事件。"""

    version: int
    built_at: datetime
    as_of: object  # 最新交易日
    stocks: pd.DataFrame  # symbol, name, group_codes, listed_date, history_from
    bars: dict[str, pd.DataFrame]  # symbol → trade_date, open/high/low/close, volume, 指標
    actions: pd.DataFrame  # symbol, ex_date, cash_dividend, split_ratio
    signals: pd.DataFrame  # symbol, trade_date, strategy（資料庫中每日更新算出的訊號，供與研究的計算比對）


def _read(conn, sql: str, symbols: list[str]) -> pd.DataFrame:
    return pd.DataFrame(conn.execute(text(sql), {"symbols": symbols}).mappings().all())


def build(path: Path = CACHE_PATH) -> MarketData:
    """唯讀連線讀出全部資料（一次連線、每張表一個查詢），寫入快取檔。"""
    symbols = sorted(WATCHLIST)
    engine = create_engine(build_database_url(), connect_args={"connect_timeout": 15, "options": "-c default_transaction_read_only=on"})
    indicator_columns = ", ".join(f"i.{c}" for c in INDICATOR_COLUMNS)
    with engine.connect() as conn:
        stocks = _read(conn, "SELECT symbol, name, group_codes, listed_date, history_from FROM stocks WHERE symbol = ANY(:symbols) ORDER BY symbol", symbols)
        prices = _read(conn, f"""
            SELECT p.symbol, p.trade_date, p.open_price, p.high_price, p.low_price, p.close_price, p.volume, {indicator_columns}
            FROM stock_prices p LEFT JOIN stock_indicators i ON i.symbol = p.symbol AND i.trade_date = p.trade_date
            WHERE p.symbol = ANY(:symbols) ORDER BY p.symbol, p.trade_date""", symbols)
        actions = _read(conn, "SELECT symbol, ex_date, cash_dividend, split_ratio FROM stock_actions WHERE symbol = ANY(:symbols) ORDER BY symbol, ex_date", symbols)
        signals = _read(conn, "SELECT symbol, trade_date, strategy FROM strategy_signals WHERE symbol = ANY(:symbols) ORDER BY symbol, trade_date, strategy", symbols)
    engine.dispose()

    # 價格與指標轉成浮點數（研究計算用；資料庫為 NUMERIC）
    numeric = ["open_price", "high_price", "low_price", "close_price", *INDICATOR_COLUMNS]
    prices[numeric] = prices[numeric].astype(float)
    for column in ("cash_dividend", "split_ratio"):
        actions[column] = actions[column].astype(float)
    bars = {symbol: frame.drop(columns="symbol").reset_index(drop=True) for symbol, frame in prices.groupby("symbol", sort=True)}

    data = MarketData(CACHE_VERSION, datetime.now(), prices["trade_date"].max(), stocks, bars, actions, signals)
    save(data, path)
    return data


def save(data: MarketData, path: Path = CACHE_PATH) -> None:
    # 存成一般的 dict（不存類別本身）：以 python -m 執行時類別屬於 __main__，其他程式會讀不回來
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as f:
        pickle.dump(vars(data), f)


def load(path: Path = CACHE_PATH) -> MarketData:
    """讀取快取；沒有快取或版本不符時請先執行 python -m research.cache。"""
    if not path.exists():
        raise FileNotFoundError(f"沒有研究快取 {path}，請先執行 python -m research.cache")
    with path.open("rb") as f:
        data = pickle.load(f)
    if not isinstance(data, dict) or data.get("version") != CACHE_VERSION:
        raise ValueError("研究快取的格式已更新，請重新執行 python -m research.cache")
    return MarketData(**data)


def describe(data: MarketData) -> list[str]:
    rows = sum(len(frame) for frame in data.bars.values())
    first = min(frame["trade_date"].iloc[0] for frame in data.bars.values())
    return [
        f"建立時間 {data.built_at:%Y-%m-%d %H:%M}，資料到 {data.as_of}",
        f"股票 {len(data.bars)} 檔、日 K {rows:,} 筆（最早 {first}）",
        f"除權息與股本事件 {len(data.actions):,} 筆、策略訊號 {len(data.signals):,} 筆",
    ]


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-7s %(message)s", datefmt="%H:%M:%S")
    parser = argparse.ArgumentParser(description="研究用的本機資料快取")
    parser.add_argument("--check", action="store_true", help="只顯示快取的內容摘要，不連線資料庫")
    args = parser.parse_args()
    try:
        data = load() if args.check else build()
    except (ConfigError, FileNotFoundError, ValueError) as exc:
        logger.error("%s", exc)
        return 2
    for line in describe(data):
        logger.info(line)
    if not args.check:
        logger.info("已寫入 %s（%.1f MB）", CACHE_PATH, CACHE_PATH.stat().st_size / 1024 ** 2)
    return 0


if __name__ == "__main__":
    sys.exit(main())
