"""策略回測單元測試：python -m pytest（以手工資料驗證每條規則，不連線資料庫）"""

import math
import statistics
from datetime import date, timedelta

import pytest

from scraper.backtest import (
    BUY_FEE,
    EXIT_RULES,
    SELL_FEE,
    SWING,
    SELL_TAX_ETF,
    SELL_TAX_STOCK,
    Bars,
    ExitRule,
    Trade,
    baseline_returns,
    buy_and_hold_curve,
    buy_and_hold_return,
    combined_curve,
    curve_metrics,
    entry_signals,
    equity_curve,
    in_market_days,
    net_return,
    rules_for,
    clustered_interval,
    run_strategy,
    simulate,
    summarize,
    t_critical,
    wilson_interval,
)

DAY0 = date(2026, 1, 5)


def bars(opens, closes, atr=1.0, dividends=None, etf=False, start=0, ma60=None):
    days = [DAY0 + timedelta(days=i) for i in range(len(closes))]
    return Bars(
        dates=days, open=list(map(float, opens)), close=list(map(float, closes)),
        atr=[float(atr)] * len(closes),
        ma60=[math.nan] * len(closes) if ma60 is None else list(map(float, ma60)),
        dividends={days[i]: d for i, d in (dividends or {}).items()},
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
    assert [d for d, _, _ in returns] == b.dates[1:4]  # 訊號日 0、1、2 隔天進場且可完整持有 3 天；之後的還沒出場
    assert all(r == pytest.approx(-0.00585, abs=1e-5) for _, r, _ in returns)


def test_wilson_interval_known_value():
    low, high = wilson_interval(6, 10)
    assert low == pytest.approx(0.3127, abs=1e-4) and high == pytest.approx(0.8318, abs=1e-4)


def base(*returns, day=0):
    """任意日進場的（進場日, 報酬）。"""
    return [(DAY0 + timedelta(days=day), r, None) for r in returns]


def closed_trade(r, day=0, days=5):
    d = DAY0 + timedelta(days=day)
    return Trade(d, d, 100.0, d, 100.0, "hold", 0.0, days, r)


def test_summarize_metrics():
    trades = [closed_trade(0.10, 0), closed_trade(-0.05, 1), closed_trade(-0.02, 2), closed_trade(0.04, 3)]
    open_trade = Trade(DAY0, DAY0 + timedelta(days=9), 100.0, None, None, None, 0.0, 2, None)
    stats = summarize({"2330.TW": trades + [open_trade]}, {"2330.TW": base(0.01, -0.01)})
    assert stats["trades"] == 4 and stats["open_trades"] == 1 and stats["wins"] == 2
    assert stats["win_rate"] == 0.5
    assert stats["avg_win"] == pytest.approx(0.07) and stats["avg_loss"] == pytest.approx(-0.035)
    assert stats["payoff_ratio"] == pytest.approx(2.0)
    assert stats["profit_factor"] == pytest.approx(0.14 / 0.07)
    assert stats["max_loss"] == -0.05 and stats["max_consecutive_losses"] == 2
    assert stats["baseline_win_rate"] == 0.5 and stats["baseline_avg_return"] == pytest.approx(0.0)


def test_t_critical_matches_t_table():
    assert t_critical(10) == pytest.approx(2.228, abs=0.005)
    assert t_critical(30) == pytest.approx(2.042, abs=0.002)
    assert t_critical(1000) == pytest.approx(1.962, abs=0.002)


def test_excess_return_compares_with_same_stock_baseline():
    # A 股任意日平均 +1%、B 股 +5%；A 的兩筆交易各 +3%（超額 +2%），B 的一筆 +5%（超額 0）
    stats = summarize(
        {"A": [closed_trade(0.03, 0), closed_trade(0.03, 1)], "B": [closed_trade(0.05, 2)]},
        {"A": base(0.0, 0.02), "B": base(0.04, 0.06)},
    )
    assert stats["excess_return"] == pytest.approx((0.02 + 0.02 + 0.0) / 3)
    # 基準依交易所屬股票加權，平均報酬 − 基準 = 超額報酬
    assert stats["avg_return"] - stats["baseline_avg_return"] == pytest.approx(stats["excess_return"])


def test_has_edge_only_when_interval_above_zero():
    # 每筆間隔 31 天，分散在不同月份（區間依進場月份分群計算）
    steady = [closed_trade(0.03 + 0.001 * (i % 3), i * 31) for i in range(30)]  # 穩定比基準高約 3%
    noisy = [closed_trade(0.30 if i % 2 else -0.27, i * 31) for i in range(30)]  # 平均也略高，但波動很大
    baseline = {"A": base(0.0, 0.0)}
    edge = summarize({"A": steady}, baseline)
    assert edge["has_edge"] and edge["excess_low"] > 0
    no_edge = summarize({"A": noisy}, baseline)
    assert not no_edge["has_edge"] and no_edge["excess_low"] < 0 < no_edge["excess_high"]
    assert summarize({"A": [closed_trade(0.05)]}, baseline)["has_edge"] is False  # 只有 1 筆無法估計區間


def test_robustness_requires_both_halves_above_baseline():
    split = DAY0 + timedelta(days=1000)
    # 前半段基準 0%、後半段基準 +5%（例如後半段是大多頭）；每筆間隔 31 天，分散在不同月份
    baseline = {"A": base(0.0, day=0) + base(0.05, day=1000)}
    early = [closed_trade(0.03 + 0.001 * (i % 3), i * 31) for i in range(30)]  # 前半段，比同期基準高 3%
    late_good = [closed_trade(0.08 + 0.001 * (i % 3), 1000 + i * 31) for i in range(30)]  # 後半段，比同期基準高 3%
    late_bad = [closed_trade(0.04 + 0.001 * (i % 3), 1000 + i * 31) for i in range(30)]  # 後半段，比同期基準低 1%

    good = summarize({"A": early + late_good}, baseline, split)
    assert good["trades_first"] == 30 and good["trades_second"] == 30
    assert good["excess_first"] == pytest.approx(0.031, abs=1e-3) and good["excess_second"] == pytest.approx(0.031, abs=1e-3)
    assert good["has_edge"]

    bad = summarize({"A": early + late_bad}, baseline, split)
    assert bad["excess_second"] < 0
    assert bad["excess_low"] > 0 and not bad["has_edge"]  # 整體區間雖在 0 以上，但後半段輸給同期基準 → 不算有優勢


def test_exit_rules_are_fixed():
    assert list(EXIT_RULES) == ["hold_5", "hold_10", "hold_20", "trailing_atr", "ma60_exit", "trailing_nocap"]
    assert [code for code, rule in EXIT_RULES.items() if rule.horizon == SWING] == ["ma60_exit", "trailing_nocap"]


def test_swing_strategies_only_use_swing_exits():
    assert list(rules_for("high_120")) == ["ma60_exit", "trailing_nocap"]
    assert list(rules_for("golden_cross")) == list(EXIT_RULES)


def test_ma60_exit_sells_next_open_after_close_below_ma60():
    # 第 4 天收盤 9.5 跌破季線 10 → 第 5 天開盤 9.4 賣出
    b = bars(opens=[10, 11, 12, 12, 10, 9.4, 9], closes=[11, 12, 13, 12, 9.5, 9, 9], ma60=[10] * 7)
    t = simulate(b, 0, EXIT_RULES["ma60_exit"])
    assert t.exit_reason == "ma_break" and t.exit_date == b.dates[5] and t.exit_price == 9.4 and t.holding_days == 5


def test_ma60_exit_stays_open_when_breaking_on_last_day():
    b = bars(opens=[10, 11, 12], closes=[11, 12, 9], ma60=[10, 10, 10])
    assert simulate(b, 0, EXIT_RULES["ma60_exit"]).exit_date is None


def test_trailing_without_cap_holds_beyond_60_days():
    n = 130
    b = bars(opens=[10 + i for i in range(n)], closes=[10.5 + i for i in range(n)])
    capped = simulate(b, 0, EXIT_RULES["trailing_atr"])
    assert capped.exit_reason == "max_hold" and capped.holding_days == 60
    assert simulate(b, 0, EXIT_RULES["trailing_nocap"]).exit_date is None  # 一路上漲：不設上限就一直持有


def test_equity_curve_compounds_trades_and_marks_open_position():
    rule = ExitRule("持有 2 日", hold_days=2)
    b = bars(opens=[10, 10, 10, 10, 10, 10, 10], closes=[10, 11, 12, 10, 10, 12, 13])
    trades = run_strategy(b, [0, 3], "golden_cross", rule)  # 第 1~2 天、第 4~5 天
    curve = equity_curve(b, trades)
    first, second = trades[0].net_return, trades[1].net_return
    assert curve[0] == 1.0  # 進場前是現金
    assert curve[1] == pytest.approx(11 / (10 * (1 + BUY_FEE)))  # 持有中以收盤計值（未扣賣出成本）
    assert curve[2] == pytest.approx(1 + first) and curve[3] == pytest.approx(1 + first)  # 出場後現金不變
    assert curve[-1] == pytest.approx((1 + first) * (1 + second))
    assert in_market_days(b, trades) == [False, True, False, False, True, False, False]


def test_buy_and_hold_curve_matches_buy_and_hold_return():
    b = bars(opens=[10, 10, 11, 12, 9], closes=[10, 11, 12, 9, 13], dividends={3: 0.5})
    curve = buy_and_hold_curve(b)
    assert curve[-1] == pytest.approx(1 + buy_and_hold_return(b))
    assert curve[3] == pytest.approx((9 + 0.5) / (10 * (1 + BUY_FEE)))  # 除息日起計入股利


def test_curve_metrics():
    days = [date(2020, 1, 1), date(2021, 1, 1), date(2022, 1, 1)]
    m = curve_metrics(days, [1.0, 2.0, 1.21], [True, True, False])
    assert m["total_return"] == pytest.approx(0.21)
    assert m["cagr"] == pytest.approx(1.21 ** (365.25 / 731) - 1)
    assert m["max_drawdown"] == pytest.approx(-0.395)
    assert m["exposure"] == pytest.approx(2 / 3)


def test_combined_curve_equal_weight_and_fills_missing_days():
    d = [date(2026, 1, i) for i in (5, 6, 7)]
    days, values = combined_curve([(d, [1.0, 1.2, 1.4]), ([d[0], d[2]], [1.0, 0.8])])
    assert days == d
    assert values == pytest.approx([1.0, 1.1, 1.1])  # 第二檔 1/6 沒有資料，沿用前一天 1.0


def test_ma60_exit_baseline_is_stratified_by_signal_day_position():
    # 基準：站上季線那層平均 +5%、季線之下那層平均 -1%（隔天就被迫出場）
    baseline = {"A": [(DAY0, 0.05, True), (DAY0, 0.05, True), (DAY0, -0.01, False), (DAY0, -0.01, False)]}
    above = [Trade(DAY0, DAY0, 100.0, DAY0, 100.0, "ma_break", 0.0, 5, 0.06, True) for _ in range(3)]
    stats = summarize({"A": above}, baseline)
    # 只和站上季線那層比較：超額 +1%，不是和全部基準（+2%）比較的 +4%
    assert stats["excess_return"] == pytest.approx(0.01)
    assert stats["baseline_avg_return"] == pytest.approx(0.05)


def test_simulate_records_stratum_only_for_ma60_exit():
    b = bars(opens=[10, 11, 12, 12, 10, 9.4, 9], closes=[11, 12, 13, 12, 9.5, 9, 9], ma60=[10.5, 10, 10, 10, 10, 10, 10])
    assert simulate(b, 0, EXIT_RULES["ma60_exit"]).stratum is True  # 訊號日收盤 11 > 季線 10.5
    assert simulate(b, 4, EXIT_RULES["ma60_exit"]).stratum is False  # 訊號日收盤 9.5 < 季線 10
    assert simulate(b, 0, EXIT_RULES["hold_5"]).stratum is None


def test_clustered_interval_equals_t_interval_when_each_trade_is_its_own_cluster():
    values = [0.03, -0.01, 0.02, 0.05, -0.02, 0.01]
    low, high, groups = clustered_interval(values, list(range(len(values))))
    half = t_critical(len(values) - 1) * statistics.stdev(values) / math.sqrt(len(values))
    assert groups == 6
    assert low == pytest.approx(statistics.fmean(values) - half) and high == pytest.approx(statistics.fmean(values) + half)


def test_duplicated_trades_in_same_month_do_not_narrow_the_interval():
    # 同一個月 3 檔股票走勢相同（報酬完全一樣）：資訊量等於 1 筆，區間不應因此變窄
    values = [0.03, -0.01, 0.02, 0.05, -0.02]
    single = clustered_interval(values, list(range(5)))
    tripled = clustered_interval([v for v in values for _ in range(3)], [m for m in range(5) for _ in range(3)])
    assert tripled[0] == pytest.approx(single[0]) and tripled[1] == pytest.approx(single[1])
    naive_half = t_critical(14) * statistics.stdev(values * 3) / math.sqrt(15)
    assert tripled[1] - tripled[0] > 2 * naive_half  # 若當成 15 筆獨立樣本，區間會窄很多


def test_too_few_months_gives_no_interval():
    assert clustered_interval([0.01, 0.02, 0.03], [1, 1, 2])[:2] == (None, None)
