from datetime import date, timedelta

from research.flows import drop_events, flow_states, percentiles, rolling_sum


def days(n: int) -> list[date]:
    return [date(2024, 1, 1) + timedelta(days=k) for k in range(n)]


def test_trust_streak_and_foreign_10d():
    dates = days(12)
    flows = {d: {"foreign": 1 if k >= 2 else -100, "trust": 5 if k in (3, 4, 5, 7) else 0, "dealer": 0} for k, d in enumerate(dates)}
    states = flow_states(dates, flows)
    assert states["trust_streak3"] == {5}
    # 外資 10 日累計：第 9 天（含前兩天 -100）為負，第 11 天起 10 天都 +1
    assert 9 not in states["foreign_10d"]
    assert 11 in states["foreign_10d"]


def test_foreign_10d_needs_complete_days():
    dates = days(10)
    flows = {d: {"foreign": 5, "trust": 0, "dealer": 0} for d in dates[1:]}  # 第 1 天沒有資料
    assert flow_states(dates, flows)["foreign_10d"] == set()


def test_drop_events_counted_once():
    closes = [100.0] * 20 + [101, 95, 89, 85, 88, 92, 100] + [100.0] * 70 + [100, 88]
    dates = days(len(closes))
    events = drop_events(dates, closes)
    assert len(events) == 2  # 85、88 仍在第一次之內，不重複算
    assert events[0][0] == dates[20] and events[0][1] == dates[22]  # 高點與跌破 10% 的那天
    assert round(events[0][2], 4) == round(89 / 101 - 1, 4)


def test_rolling_and_percentiles_use_only_past():
    values = rolling_sum([1, 2, 3, 4], 2)
    assert values == [None, 3, 5, 7]
    ranks = percentiles([1.0, 2.0, 3.0, 0.5], 3)
    assert ranks[:2] == [None, None]
    assert ranks[2] > ranks[3]  # 最新的值在近 3 天中最高 / 最低
    assert percentiles([1.0, 2.0, 3.0, 0.5], 3)[2] == percentiles([1.0, 2.0, 3.0], 3)[2]  # 不受之後的值影響
