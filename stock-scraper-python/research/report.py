"""R1-6 研究報告：重新計算各版本與檢查，產生一頁網頁（research/cache/report.html，不進 Git）。

    python -m research.report

數字全部在這裡由快取與模擬器重新算出（不手抄），版本設定見 VERSIONS；頁面版型在 report_template.html。
"""

import json
import logging
import pickle
import sys
from collections import defaultdict
from dataclasses import replace
from datetime import date
from pathlib import Path

from research import cache
from research.data import ETF, universe
from research.metrics import summary
from research.scan import CORE_WEIGHTS, RESULT_PATH, Key, checks, evaluate_curve, plan_for
from research.simulator import benchmark_plan, simulate
from scraper.backtest import buy_and_hold_return
from scraper.portfolio import benchmark_curve

logger = logging.getLogger(__name__)

TEMPLATE = Path(__file__).resolve().parent / "report_template.html"
OUTPUT = cache.CACHE_PATH.parent / "report.html"
SIGNALS = "high_120+golden_cross_trend"
VERSIONS = {"核心為主": 0.7, "均衡": 0.5, "策略為主": 0.3}  # 版本 → 核心比例
RECOMMENDED = "均衡"


def key_for(core: float, frequency: str, exit_: str = "pct10") -> Key:
    return Key(SIGNALS, exit_, core, "risk", "no_new60", frequency)


def weekly_points(dates: list[date], values: list[float]) -> list[list]:
    """每週最後一個交易日一點（圖表用，約 570 點）。"""
    points = []
    for i, day in enumerate(dates):
        last_of_week = i == len(dates) - 1 or dates[i + 1].isocalendar()[:2] != day.isocalendar()[:2]
        if last_of_week:
            points.append([day.isoformat(), round(values[i])])
    return points


def drawdowns(points: list[list]) -> list[list]:
    peak, out = 0.0, []
    for day, value in points:
        peak = max(peak, value)
        out.append([day, round(value / peak - 1, 4)])
    return out


def metrics_row(m: dict) -> dict:
    keep = ("cagr", "max_drawdown", "calmar", "calmar_first", "calmar_second", "underwater_days", "worst_year", "total_return")
    return {k: (round(m[k], 4) if isinstance(m[k], float) else m[k]) for k in keep}


def build() -> dict:
    data = cache.load()
    stocks = universe(data)
    with RESULT_PATH.open("rb") as f:
        scan = pickle.load(f)
    split = scan["split_date"]

    bench = simulate(stocks, benchmark_plan())
    etf_metrics = evaluate_curve(bench.dates, bench.equity, split)
    series = [{"name": "0050 買進持有", "role": "benchmark", "points": weekly_points(bench.dates, bench.equity),
               "years": {str(y): round(v, 4) for y, v in summary(bench.dates, bench.equity)["years"].items()}}]
    rows = [{"name": "0050 買進持有", "core": 1.0, "daily": metrics_row(etf_metrics), "weekly": None}]

    gains = sorted(((buy_and_hold_return(s.bars) or 0, sym) for sym, s in stocks.items() if sym != ETF), reverse=True)
    top5 = [sym for _, sym in gains[:5]]
    rest = {s: v for s, v in stocks.items() if s not in top5}
    version_checks = []
    for name, core in VERSIONS.items():
        row = {"name": name, "core": core}
        for frequency in ("daily", "weekly"):
            key = key_for(core, frequency)
            run = simulate(stocks, plan_for(key))
            m = evaluate_curve(run.dates, run.equity, split)
            row[frequency] = {**metrics_row(m), "trades": run.trades, "invested": round(sum(run.invested) / len(run.invested), 3)}
            neighbors = [key_for(core, frequency, e) for e in ("pct15", "atr3")]
            neighbors += [key_for(c, frequency) for c in CORE_WEIGHTS if abs(c - core) == 0.2]
            neighbor_calmars = [evaluate_curve(*(lambda r: (r.dates, r.equity))(simulate(stocks, plan_for(k))), split)["calmar"] for k in neighbors]
            randoms = sorted(summary(*(lambda r: (r.dates, r.equity))(simulate(stocks, replace(plan_for(key), rank="random", seed=s))))["calmar"] for s in range(10))
            without_top = summary(*(lambda r: (r.dates, r.equity))(simulate(rest, plan_for(key))))
            version_checks.append({
                "name": name, "frequency": frequency,
                "halves": [round(m["calmar_first"], 2), round(m["calmar_second"], 2)],
                "neighbors": [round(v, 2) for v in neighbor_calmars],
                "random": [round(randoms[0], 2), round(randoms[5], 2), round(randoms[-1], 2)],
                "without_top5": round(without_top["calmar"], 2),
                "return_gap": round(m["cagr"] - etf_metrics["cagr"], 4),
            })
            if frequency == "daily":
                series.append({"name": name, "role": "version", "points": weekly_points(run.dates, run.equity),
                               "years": {str(y): round(v, 4) for y, v in summary(run.dates, run.equity)["years"].items()}})
        rows.append(row)
        logger.info("%s 完成", name)

    # 掃描摘要：各參數的通過率
    c = checks(scan)
    ok = {k: bool(v["version"] and v["calmar"] and v["return"] and v["bias"] and v["stable"]) for k, v in c.items()}
    rates = {}
    for field in Key._fields:
        groups = defaultdict(list)
        for k, passed in ok.items():
            groups[getattr(k, field)].append(passed)
        rates[field] = [[str(value), round(sum(p) / len(p), 3)] for value, p in groups.items()]
    results = {Key(*t): m for t, m in scan["results"].items()}
    first_rank = sorted(results, key=lambda k: -(results[k]["calmar_first"] or -9))[:100]

    hold_dates = sorted({d for s in stocks.values() for d in s.bars.dates[s.bars.start:]})
    same = summary(hold_dates, benchmark_curve(stocks, hold_dates))
    rest_dates = sorted({d for s in rest.values() for d in s.bars.dates[s.bars.start:]})
    same_rest = summary(rest_dates, benchmark_curve(rest, rest_dates))
    return {
        "asOf": str(data.as_of), "period": [bench.dates[0].isoformat(), bench.dates[-1].isoformat()], "split": split.isoformat(),
        "stocks": len(stocks), "recommended": RECOMMENDED, "series": series, "rows": rows, "checks": version_checks,
        "scan": {
            "total": len(c), "passed": sum(ok.values()), "rates": rates,
            "firstHalfTop100BeatEtfSecond": sum(1 for k in first_rank if results[k]["calmar_second"] > etf_metrics["calmar_second"]),
        },
        "bias": {
            "sameStocks": metrics_row({**same, "calmar_first": None, "calmar_second": None}),
            "top5": [[sym, round(g, 1)] for g, sym in gains[:5]],
            "withoutTop5": metrics_row({**same_rest, "calmar_first": None, "calmar_second": None}),
        },
        "legacy": {"runs": 240},
    }


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-7s %(message)s", datefmt="%H:%M:%S")
    payload = build()
    html = TEMPLATE.read_text(encoding="utf-8").replace("__REPORT_DATA__", json.dumps(payload, ensure_ascii=False))
    OUTPUT.write_text(html, encoding="utf-8")
    logger.info("已寫入 %s（%.0f KB）", OUTPUT, OUTPUT.stat().st_size / 1024)
    return 0


if __name__ == "__main__":
    sys.exit(main())
