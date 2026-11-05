"""研究快取：存檔後讀回相同、格式版本不符時要求重建。"""

import pickle
from datetime import date, datetime

import pandas as pd
import pytest

from research import cache


def _sample() -> cache.MarketData:
    bars = {"2330.TW": pd.DataFrame({"trade_date": [date(2026, 11, 3), date(2026, 11, 4)], "close_price": [1000.0, 1010.0]})}
    return cache.MarketData(
        cache.CACHE_VERSION, datetime(2026, 11, 5, 9, 0), date(2026, 11, 4),
        pd.DataFrame({"symbol": ["2330.TW"], "name": ["台積電"]}), bars,
        pd.DataFrame({"symbol": ["2330.TW"], "ex_date": [date(2026, 9, 1)], "cash_dividend": [5.0], "split_ratio": [None]}),
        pd.DataFrame({"symbol": ["2330.TW"], "trade_date": [date(2026, 11, 4)], "strategy": ["high_120"]}),
    )


def test_round_trip(tmp_path):
    path = tmp_path / "market.pkl"
    cache.save(_sample(), path)
    loaded = cache.load(path)
    assert loaded.as_of == date(2026, 11, 4)
    pd.testing.assert_frame_equal(loaded.bars["2330.TW"], _sample().bars["2330.TW"])
    assert cache.describe(loaded)[1] == "股票 1 檔、日 K 2 筆（最早 2026-11-03）"


def test_missing_or_outdated_cache(tmp_path):
    with pytest.raises(FileNotFoundError, match="python -m research.cache"):
        cache.load(tmp_path / "none.pkl")
    old = tmp_path / "old.pkl"
    old.write_bytes(pickle.dumps({**vars(_sample()), "version": cache.CACHE_VERSION - 1}))
    with pytest.raises(ValueError, match="重新執行"):
        cache.load(old)
