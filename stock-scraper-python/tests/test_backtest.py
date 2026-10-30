"""策略回測單元測試：python -m pytest（以手工資料驗證每條規則，不連線資料庫）"""

import math
from datetime import date, timedelta

import pytest

from scraper.backtest import (
    BUY_FEE,
    EXIT_RULES,
    SELL_FEE,
    SELL_TAX_ETF,
    SELL_TAX_STOCK,
    Bars,
    ExitRule,
    baseline_returns,
    entry_signals,
    net_return,
    run_strategy,
    simulate,
    summarize,
    wilson_interval,
)

DAY0 = date(2026, 1, 5)


def bars(opens, closes, atr=1.0, dividends=None, etf=False, start=0):
    days = [DAY0 + timedelta(days=i) for i in range(len(closes))]
    return Bars(
        dates=days, open=list(map(float, opens)), close=list(map(float, closes)),
        atr=[float(atr)] * len(closes), dividends={days[i]: d for i, d in (dividends or {}).items()},
        is_etf=etf, start=start,
    )


HOLD_3 = ExitRule("持有 3 日", hold_days=3)


def test_entry_is_next_day_open_not_signal_day_close():
    b = bars(opens=[10, 11, 12, 13, 14], closes=[10.5, 11.5, 12.5, 13.5, 14.5])
    t = simulate(b, 0, HOLD_3)
    assert t.signal_date == b.dates[0] and t.entry_date == b.dates[1]
    assert t.entry_price == 11.0  # 隔天開盤，不是訊號日收盤 10.5
    assert t.exit_date == b.dates[3] and t.exit_price == 13.5  # 含進場日共 3 天，第 3 天收盤
    assert t.holding_days == 3 and t.exit_reason == "hold"


def test_costs_for_stock_and_etf():
    stock = net_return(100, 110, 0, etf=False)
    etf = net_return(100, 110, 0, etf=True)
    assert stock == pytest.approx((110 * (1 - SELL_FEE - SELL_TAX_STOCK)) / (100 * (1 + BUY_FEE)) - 1)
    assert etf == pytest.approx((110 * (1 - SELL_FEE - SELL_TAX_ETF)) / (100 * (1 + BUY_FEE)) - 1)
    assert net_return(100, 100, 0, etf=False) == pytest.approx(-0.00585, abs=1e-5)  # 價格不變，來回成本約 0.585%


def test_dividend_counted_only_after_entry_day():
    # 第 2 天（index 2）除息 1 元：第 0 天訊號、第 1 天進場 → 領得到
    b = bars(opens=[10, 10, 9, 9, 9], closes=[10, 10, 9, 9, 9], dividends={2: 1.0})
    assert simulate(b, 0, HOLD_3).dividends == 1.0
    # 第 1 天訊號、第 2 天（除息日當天）才進場 → 領不到
    assert simulate(b, 1, HOLD_3).dividends == 0.0


def test_skip_when_opening_at_limit_up():
    b = bars(opens=[100, 110, 112, 113, 114], closes=[100, 111, 112, 113, 114])
    assert simulate(b, 0, HOLD_3) is None  # 開盤 +10%，買不到
    b2 = bars(opens=[100, 109, 112, 113, 114], closes=[100, 111, 112, 113, 114])
    assert simulate(b2, 0, HOLD_3) is not None  # 開盤 +9%，買得到


def test_limit_up_uses_ex_dividend_reference_price():
    # 前日收盤 100，當天除息 5 元 → 參考價 95；開盤 104.5 已是 +10%
    b = bars(opens=[100, 104.5, 105, 106, 107], closes=[100, 105, 105, 106, 107], dividends={1: 5.0})
    assert simulate(b, 0, HOLD_3) is None


def test_trade_still_open_when_data_ends():
    b = bars(opens=[100, 101, 102], closes=[100, 101, 102])
    t = simulate(b, 0, HOLD_3)
    assert t.exit_date is None and t.net_return is None and t.holding_days == 2


def test_trailing_stop_exits_next_open_after_close_breaks_line():
    rule = ExitRule("移動停利", trailing_atr=2.0, max_days=60)
    # 進場後最高收盤 20，ATR 1 → 停利線 18；第 5 天收盤 17.5 跌破 → 第 6 天開盤 17 賣出
    b = bars(opens=[10, 10, 15, 19, 19, 18, 17, 16], closes=[10, 14, 18, 20, 19, 17.5, 16, 15])
    t = simulate(b, 0, rule)
    assert t.exit_reason == "trailing" and t.exit_date == b.dates[6] and t.exit_price == 17.0
    assert t.holding_days == 6


def test_trailing_stop_respects_max_days():
    rule = ExitRule("移動停利", trailing_atr=3.0, max_days=4)
    b = bars(opens=[10, 10, 11, 12, 13, 14, 15], closes=[10, 11, 12, 13, 14, 15, 16])
    t = simulate(b, 0, rule)
    assert t.exit_reason == "max_hold" and t.exit_date == b.dates[4] and t.exit_price == 14.0


def test_trailing_ignores_warmup_nan_atr():
    rule = ExitRule("移動停利", trailing_atr=1.0, max_days=3)
    b = bars(opens=[10, 10, 5, 5], closes=[10, 10, 5, 5], atr=math.nan)
    assert simulate(b, 0, rule).exit_reason == "max_hold"  # ATR 未知時不觸發移動停利


def test_no_new_entry_while_holding():
    b = bars(opens=range(100, 110), closes=range(100, 110))  # 每天小漲，不會觸發「開盤漲停」
    trades = run_strategy(b, [0, 1, 2, 3, 5], "golden_cross", HOLD_3)
    # 第 0 天訊號：第 1~3 天持有。第 1、2 天的訊號在持有中略過；第 3 天訊號 → 第 4 天進場（前一筆已在第 3 天出場）
    assert [t.signal_date for t in trades] == [b.dates[0], b.dates[3]]


def test_signals_before_backtest_start_are_ignored():
    b = bars(opens=range(100, 110), closes=range(100, 110), start=4)
    trades = run_strategy(b, [1, 5], "golden_cross", HOLD_3)
    assert [t.signal_date for t in trades] == [b.dates[5]]


def test_state_strategy_only_enters_on_first_day():
    assert entry_signals([3, 4, 5, 8, 9], state=True) == [3, 8]
    assert entry_signals([3, 4, 5], state=False) == [3, 4, 5]


def test_baseline_enters_every_day():
    b = bars(opens=[10, 10, 10, 10, 10, 10], closes=[10, 10, 10, 10, 10, 10])
    returns = baseline_returns(b, HOLD_3)
    assert len(returns) == 3  # 訊號日 0、1、2 可完整持有 3 天；之後的還沒出場
    assert all(r == pytest.approx(-0.00585, abs=1e-5) for r in returns)


def test_wilson_interval_known_value():
    low, high = wilson_interval(6, 10)
    assert low == pytest.approx(0.3127, abs=1e-4) and high == pytest.approx(0.8318, abs=1e-4)


def test_summarize_metrics():
    b = bars(opens=[10] * 3, closes=[10] * 3)

    def trade(r, days=5):
        return type("T", (), {"net_return": r, "holding_days": days})()

    stats = summarize([trade(0.10), trade(-0.05), trade(-0.02), trade(0.04), trade(None)], baseline=[0.01, -0.01])
    assert stats["trades"] == 4 and stats["open_trades"] == 1 and stats["wins"] == 2
    assert stats["win_rate"] == 0.5
    assert stats["avg_win"] == pytest.approx(0.07) and stats["avg_loss"] == pytest.approx(-0.035)
    assert stats["payoff_ratio"] == pytest.approx(2.0)
    assert stats["profit_factor"] == pytest.approx(0.14 / 0.07)
    assert stats["max_loss"] == -0.05 and stats["max_consecutive_losses"] == 2
    assert stats["baseline_win_rate"] == 0.5 and stats["baseline_avg_return"] == pytest.approx(0.0)
    assert b.start == 0


def test_exit_rules_are_fixed():
    assert list(EXIT_RULES) == ["hold_5", "hold_10", "hold_20", "trailing_atr"]
