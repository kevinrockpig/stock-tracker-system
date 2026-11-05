"""R2 研究報告：重新計算月營收、法人訊號與事件研究，產生一頁網頁（research/cache/report_r2.html，不進 Git）。

    python -m research.report_r2

數字全部在這裡重新算出（不手抄）；樣式沿用 R1 報告（report_template.html 的 <style>），內容在 report_r2_template.html。
"""

import json
import logging
import re
import sys
from pathlib import Path

from research import cache, finmind, flows, revenue
from research.data import universe
from research.report import TEMPLATE as R1_TEMPLATE
from research.scan import CORE_WEIGHTS, beats_calmar

logger = logging.getLogger(__name__)

TEMPLATE = Path(__file__).resolve().parent / "report_r2_template.html"
OUTPUT = cache.CACHE_PATH.parent / "report_r2.html"
SETTINGS = [(core, frequency) for core in (0.7, 0.5, 0.3) for frequency in revenue.FREQUENCIES]


def _metrics(m: dict) -> dict:
    return {k: round(m[k], 4) for k in ("cagr", "max_drawdown", "calmar", "calmar_first", "calmar_second") if k in m}


def grid(scan: dict, signals: list[str]) -> list[dict]:
    """每個訊號在 6 種設定（核心 × 頻率）下的結果，以及是否勝過同設定的 R1、相鄰參數勝過的比例。"""
    results = scan["results"]
    rows = []
    for signal in signals:
        cells = []
        for core, frequency in SETTINGS:
            m = results[(signal, "pct10", core, frequency)]
            base = results[("trend", "pct10", core, frequency)]
            neighbors = [(signal, e, core, frequency) for e in ("pct15", "atr3")]
            neighbors += [(signal, "pct10", c, frequency) for c in CORE_WEIGHTS if abs(c - core) == 0.2]
            cells.append({**_metrics(m), "trades": m["trades"],
                          "improves": None if signal == "trend" else beats_calmar(m, base),
                          "neighbors": None if signal == "trend" else [sum(beats_calmar(results[k], results[("trend", *k[1:])]) for k in neighbors), len(neighbors)]})
        rows.append({"signal": signal, "cells": cells})
    return rows


def build() -> dict:
    data, ext = cache.load(), finmind.load()
    base = universe(data)
    revenue_scan = revenue.run(revenue.with_revenue(base, ext.revenue))
    logger.info("月營收掃描完成")
    flow_scan = revenue.run(flows.with_flows(base, ext.flows), flows.SIGNALS)
    logger.info("法人訊號掃描完成")
    events = flows.event_data(data, ext)
    robust = flows.robustness_data(data, ext)
    logger.info("事件研究與穩健性完成")
    etf = revenue_scan["etf"]
    return {
        "asOf": str(data.as_of), "split": str(revenue_scan["split"]), "stocks": len(base),
        "etf": _metrics(etf),
        "settings": [{"core": c, "frequency": f} for c, f in SETTINGS],
        "data": {"revenue": len(ext.revenue), "flows": len(ext.flows), "defensive": {k: len(v) for k, v in ext.defensive.items()},
                 "marketDays": len(ext.market), "firstFlow": str(min(min(d) for d in ext.flows.values() if d))},
        "revenue": grid(revenue_scan, revenue.SIGNALS),
        "flows": grid(flow_scan, flows.SIGNALS),
        "robustness": [{**r, "variants": {k: _metrics(m) for k, m in r["variants"].items()},
                        "random": round(r["random"], 3), "without_top5": round(r["without_top5"], 3)} for r in robust],
        "events": {
            "start": str(events["start"]), "end": str(events["end"]),
            "rows": [{**r, "peak": str(r["peak"]), "hit": str(r["hit"])} for r in events["events"]],
            "tests": events["tests"], "forward": events["forward"],
            "params": {"drop": flows.DROP, "dropWindow": flows.DROP_WINDOW, "flowWindow": flows.FLOW_WINDOW,
                       "rankWindow": flows.RANK_WINDOW, "high": flows.HIGH, "gap": flows.EVENT_GAP},
        },
    }


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-7s %(message)s", datefmt="%H:%M:%S")
    payload = build()
    style = re.search(r"<style>.*?</style>", R1_TEMPLATE.read_text(encoding="utf-8"), re.S).group(0)
    html = (TEMPLATE.read_text(encoding="utf-8").replace("__STYLE__", style)
            .replace("__REPORT_DATA__", json.dumps(payload, ensure_ascii=False)))
    OUTPUT.write_text(html, encoding="utf-8")
    logger.info("已寫入 %s（%.0f KB）", OUTPUT, OUTPUT.stat().st_size / 1024)
    return 0


if __name__ == "__main__":
    sys.exit(main())
