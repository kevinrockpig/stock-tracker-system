"""把研究快取轉成回測用的格式（與 scraper.portfolio.load_stocks 相同的 Stock），新舊模擬器吃同一份輸入。"""

import pandas as pd

from research.cache import MarketData
from scraper.backtest import entry_signals, make_bars
from scraper.db import BACKTEST_COLUMNS
from scraper.portfolio import Stock
from scraper.strategies import STATE_STRATEGIES, evaluate_strategies

ETF = "0050.TW"


def stored_signals(data: MarketData, symbol: str) -> dict[str, set]:
    """資料庫中每日更新算出的訊號：strategy → 日期集合。"""
    rows = data.signals[data.signals["symbol"] == symbol]
    signals: dict[str, set] = {}
    for trade_date, strategy in zip(rows["trade_date"], rows["strategy"]):
        signals.setdefault(strategy, set()).add(trade_date)
    return signals


def computed_signals(frame: pd.DataFrame) -> dict[str, set]:
    """由快取的日 K 與指標重新計算的訊號（新策略會加在 strategies 或研究模組中）。"""
    result = evaluate_strategies(frame)
    return {code: set(result.loc[result[code], "trade_date"]) for code in result.columns if code != "trade_date"}


def dividends_of(data: MarketData, symbol: str) -> dict:
    rows = data.actions[(data.actions["symbol"] == symbol) & data.actions["cash_dividend"].notna()]
    return dict(zip(rows["ex_date"], rows["cash_dividend"]))


def universe(data: MarketData, symbols=None, signals: str = "stored") -> dict[str, Stock]:
    """symbols 省略為快取中全部股票；signals 為 stored（資料庫）或 computed（重新計算）。"""
    stocks = {}
    for symbol in sorted(symbols or data.bars):
        frame = data.bars[symbol]
        if frame.empty:
            continue
        bars = make_bars(symbol, frame[list(BACKTEST_COLUMNS)], dividends_of(data, symbol))
        index_of = {d: i for i, d in enumerate(bars.dates)}
        found = stored_signals(data, symbol) if signals == "stored" else computed_signals(frame)
        entries = {
            strategy: set(entry_signals([index_of[d] for d in days if d in index_of], strategy in STATE_STRATEGIES))
            for strategy, days in found.items()
        }
        stocks[symbol] = Stock(bars, index_of, entries)
    return stocks
