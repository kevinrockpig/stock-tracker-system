"""資金曲線的評估指標：除了年化報酬、最大回撤、Calmar、Sharpe（沿用 scraper.portfolio.risk_metrics），
再加上「抱不抱得住」相關的指標：最長套牢期、逐年報酬、最差年度。"""

from datetime import date

from scraper.portfolio import risk_metrics


def longest_underwater(dates: list[date], values: list[float]) -> int:
    """從前高跌下去、到重新站回前高所經過的最長日曆天數（期末仍未站回時算到最後一天）。"""
    peak_value, peak_date, longest = values[0], dates[0], 0
    for day, value in zip(dates, values):
        if value >= peak_value:
            longest = max(longest, (day - peak_date).days)
            peak_value, peak_date = value, day
    return max(longest, (dates[-1] - peak_date).days)


def yearly_returns(dates: list[date], values: list[float]) -> dict[int, float]:
    """每個年度的報酬（以前一年最後一天為起點；第一年從資料起點起算）。"""
    result, start_value, year = {}, values[0], dates[0].year
    for i, day in enumerate(dates):
        if day.year != year:
            result[year] = values[i - 1] / start_value - 1
            start_value, year = values[i - 1], day.year
    result[year] = values[-1] / start_value - 1
    return result


def summary(dates: list[date], values: list[float]) -> dict:
    metrics = risk_metrics(dates, values)
    years = yearly_returns(dates, values)
    return {
        **metrics,
        "total_return": values[-1] / values[0] - 1,
        "underwater_days": longest_underwater(dates, values),
        "worst_year": min(years.values()) if years else None,
        "years": years,
    }
