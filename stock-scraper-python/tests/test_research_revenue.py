from datetime import date, timedelta

from research.finmind import Revenue, deadline_of
from research.revenue import daily_states, month_rules


def months_from(start_year: int, values: list[float]) -> list[Revenue]:
    out = []
    for k, v in enumerate(values):
        y, m = start_year + k // 12, k % 12 + 1
        out.append(Revenue(y, m, v, deadline_of(y, m)))
    return out


def test_yoy3_needs_three_positive_months():
    # 第一年每月 100；第二年 1～2 月 110、3 月 90、4～6 月 120
    months = months_from(2020, [100] * 12 + [110, 110, 90, 120, 120, 120])
    rules = month_rules(months)
    assert not rules[13]["rev_yoy3"]  # 只有 2 個月可比
    assert not rules[14]["rev_yoy3"]  # 3 月衰退
    assert not rules[16]["rev_yoy3"]  # 3～5 月含衰退的 3 月
    assert rules[17]["rev_yoy3"]  # 4～6 月都成長


def test_3m_high_and_down():
    flat = [100] * 26
    rules = month_rules(months_from(2020, flat + [130]))
    assert rules[-1]["rev_3m_high"]
    assert not rules[-2]["rev_3m_high"]  # 年增率 0 不算
    down = month_rules(months_from(2020, [100] * 14 + [80]))
    assert down[-1]["rev_down"]
    assert not down[-1]["rev_3m_high"]


def test_avg_high_needs_24_months():
    rising = list(range(100, 124))
    assert not month_rules(months_from(2020, rising[:23]))[-1]["rev_avg_high"]
    assert month_rules(months_from(2020, rising))[-1]["rev_avg_high"]


def test_gap_breaks_rules():
    months = months_from(2020, [100] * 12 + [120] * 3)
    months.pop(5)  # 缺一個月
    assert not month_rules(months)[-1]["rev_yoy3"]


def test_state_only_after_deadline():
    # 2021 年 1～3 月成長：3 月營收的期限是 4/10，4/10 當天還不能用，4/12（下一個交易日）起才成立
    months = months_from(2020, [100] * 12 + [120] * 3)
    dates = [date(2021, 4, 9), date(2021, 4, 10), date(2021, 4, 12)]
    states = daily_states(dates, months)
    assert states["rev_yoy3"] == {2}
    # 用到的必須是期限已過的月份：4/9 時最新已知是 2 月（2/10 期限的是 1 月，3/10 期限的是 2 月）
    assert all(m.deadline < dates[2] for m in months[:15])
    assert months[14].deadline == date(2021, 4, 10)


def test_no_months_no_state():
    assert daily_states([date(2021, 1, 1) + timedelta(days=k) for k in range(3)], []) == {
        "rev_yoy3": set(), "rev_3m_high": set(), "rev_avg_high": set(), "rev_down": set()}
