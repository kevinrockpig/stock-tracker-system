"""R1-4 參數掃描：參數範圍與判斷標準在執行前固定（不依結果調整），逐一模擬並檢查「有優勢」與穩健性。

    python -m research.scan              # 掃描並把結果存到 research/cache/scan.pkl，列出摘要
    python -m research.scan --summary    # 只讀已存的結果列出摘要

「有優勢」（2026-11-05 與使用者確認）：
1. 最大回撤在可承受範圍內（版本：穩健 -20%、平衡 -30%、積極 -35%）
2. Calmar（年化 ÷ 最大回撤）高於 0050 買進持有：整段與前後兩段都要
3. 年化報酬落後 0050 不超過 3 個百分點（整段）
另外兩道檢查：
4. 倖存者偏差：觀察名單是今天的 0050 成分股，事後看都是贏家。同樣的規則只用衛星（沒有核心）時，
   Calmar 必須高於「同一批股票等額買進持有」，代表規則本身有貢獻，而不只是因為股票選得好
5. 參數敏感度：相鄰的參數（出場參數上下一格、核心比例上下一格）多數也要符合第 2 點，不能只有單一組特別好
"""

import argparse
import itertools
import logging
import pickle
import sys
import time
from dataclasses import asdict
from typing import NamedTuple

from research import cache
from research.data import universe
from research.metrics import summary
from research.simulator import ExitSpec, Plan, benchmark_plan, simulate
from scraper.portfolio import benchmark_curve, split_metrics

logger = logging.getLogger(__name__)

RESULT_PATH = cache.CACHE_PATH.parent / "scan.pkl"
CAPITAL = 1_000_000.0
RETURN_GAP = 0.03  # 年化報酬最多落後 0050 的幅度
VERSIONS = {"穩健": -0.20, "平衡": -0.30, "積極": -0.35}  # 版本 → 可承受的最大回撤

# ---- 參數範圍（執行前固定）----
STRATEGIES = [("high_120",), ("ma20_cross_ma60",), ("golden_cross_trend",), ("ma_bullish",), ("golden_cross",), ("break_ma20",)]
EXITS = {
    "atr2": ExitSpec("atr", atr_multiple=2),
    "atr3": ExitSpec("atr", atr_multiple=3),
    "atr4": ExitSpec("atr", atr_multiple=4),
    "pct10": ExitSpec("pct", pct=0.10),
    "pct15": ExitSpec("pct", pct=0.15),
    "pct20": ExitSpec("pct", pct=0.20),
    "ma60": ExitSpec("ma60"),
    "atr3_tp20": ExitSpec("atr", atr_multiple=3, take_profit=0.20),  # 漲 20% 先賣一半，其餘 3 ATR 移動停利
}
EXIT_NEIGHBORS = {"atr2": ["atr3"], "atr3": ["atr2", "atr4"], "atr4": ["atr3"],
                  "pct10": ["pct15"], "pct15": ["pct10", "pct20"], "pct20": ["pct15"], "ma60": [], "atr3_tp20": ["atr3"]}
CORE_WEIGHTS = [0.0, 0.3, 0.5, 0.7]
SIZINGS = ["equal", "risk"]
FILTERS = {"none": ("none", 60), "no_new60": ("no_new", 60), "no_new120": ("no_new", 120), "exit60": ("exit", 60)}
FREQUENCIES = ["daily", "weekly"]


class Key(NamedTuple):
    """一組參數。結果以一般的 tuple 存檔（不存類別），讀回後用 Key(*t) 還原。"""
    strategy: str
    exit: str
    core: float
    sizing: str
    filter: str
    frequency: str


def plan_for(key: Key) -> Plan:
    mode, ma = FILTERS[key.filter]
    return Plan(
        tuple(key.strategy.split("+")), EXITS[key.exit], capital=CAPITAL, core_weight=key.core,
        rebalance="yearly" if key.core > 0 else "none", sizing=key.sizing, market_filter=mode, filter_ma=ma,
        frequency=key.frequency, rank="strength",
    )


def evaluate_curve(dates, values, split_date) -> dict:
    whole = summary(dates, values)
    first, second = split_metrics(dates, values, split_date)
    return {**whole, "calmar_first": first["calmar"], "calmar_second": second["calmar"],
            "cagr_first": first["cagr"], "cagr_second": second["cagr"]}


def run(stocks) -> dict:
    bench = simulate(stocks, benchmark_plan(CAPITAL))
    split_date = bench.dates[len(bench.dates) // 2]
    etf = evaluate_curve(bench.dates, bench.equity, split_date)
    hold_dates = sorted({d for s in stocks.values() for d in s.bars.dates[s.bars.start:]})
    same_stocks = evaluate_curve(hold_dates, benchmark_curve(stocks, hold_dates), split_date)

    keys = [Key("+".join(s), e, c, z, f, q) for s, e, c, z, f, q in
            itertools.product(STRATEGIES, EXITS, CORE_WEIGHTS, SIZINGS, FILTERS, FREQUENCIES)]
    results, started = {}, time.perf_counter()
    for n, key in enumerate(keys, 1):
        r = simulate(stocks, plan_for(key))
        results[tuple(key)] = {**evaluate_curve(r.dates, r.equity, split_date), "trades": r.trades,
                        "avg_invested": sum(r.invested) / len(r.invested)}
        if n % 500 == 0:
            logger.info("已完成 %d / %d（%.0f 秒）", n, len(keys), time.perf_counter() - started)
    return {"split_date": split_date, "etf": etf, "same_stocks": same_stocks, "results": results,
            "grid": {"strategies": STRATEGIES, "exits": {k: asdict(v) for k, v in EXITS.items()}, "core": CORE_WEIGHTS,
                     "sizing": SIZINGS, "filters": FILTERS, "frequencies": FREQUENCIES}}


# ---- 判斷 ----

def beats_calmar(m: dict, etf: dict) -> bool:
    pairs = [("calmar", "calmar"), ("calmar_first", "calmar_first"), ("calmar_second", "calmar_second")]
    return all(m[a] is not None and etf[b] is not None and m[a] > etf[b] for a, b in pairs)


def checks(scan: dict) -> dict[Key, dict]:
    """每一組的各項檢查結果。"""
    etf, same = scan["etf"], scan["same_stocks"]
    results = {Key(*t): m for t, m in scan["results"].items()}
    out = {}
    for key, m in results.items():
        satellite = results.get(Key(key.strategy, key.exit, 0.0, key.sizing, key.filter, key.frequency))
        neighbors = [Key(key.strategy, e, key.core, key.sizing, key.filter, key.frequency) for e in EXIT_NEIGHBORS[key.exit]]
        i = CORE_WEIGHTS.index(key.core)
        neighbors += [Key(key.strategy, key.exit, c, key.sizing, key.filter, key.frequency)
                      for c in CORE_WEIGHTS[max(i - 1, 0):i + 2] if c != key.core]
        passing = [beats_calmar(results[n], etf) for n in neighbors if n in results]
        out[key] = {
            "calmar": beats_calmar(m, etf),
            "return": m["cagr"] is not None and m["cagr"] >= etf["cagr"] - RETURN_GAP,
            "bias": satellite is not None and satellite["calmar"] is not None and satellite["calmar"] > same["calmar"],
            "stable": bool(passing) and sum(passing) * 2 > len(passing),
            "version": next((name for name, limit in VERSIONS.items() if m["max_drawdown"] >= limit), None),
        }
    return out


def advantaged(scan: dict) -> list[tuple[Key, dict]]:
    c = checks(scan)
    return sorted(((k, scan["results"][tuple(k)]) for k, v in c.items()
                   if v["version"] and v["calmar"] and v["return"] and v["bias"] and v["stable"]),
                  key=lambda kv: -kv[1]["calmar"])


# ---- 輸出 ----

def _row(name: str, m: dict) -> str:
    return (f"{name:<52}{m['cagr']:>7.1%}{m['max_drawdown']:>8.1%}{m['calmar']:>7.2f}"
            f"{m['calmar_first']:>7.2f}{m['calmar_second']:>7.2f}{m['underwater_days']:>7}{m['worst_year']:>8.1%}")


def describe(key: Key) -> str:
    return f"{key.strategy}｜{key.exit}｜核心{key.core:.0%}｜{key.sizing}｜{key.filter}｜{key.frequency}"


def log_summary(scan: dict) -> None:
    c = checks(scan)
    header = f"{'':<52}{'年化':>7}{'回撤':>8}{'Calmar':>7}{'前段':>7}{'後段':>7}{'套牢天':>7}{'最差年':>8}"
    logger.info("切分日 %s；共 %d 組", scan["split_date"], len(c))
    logger.info(header)
    logger.info(_row("0050 買進持有（基準）", scan["etf"]))
    logger.info(_row("同一批 58 檔等額買進持有（偏差對照）", scan["same_stocks"]))
    total = len(c)
    for label, field in (("最大回撤在 -35% 以內", "version"), ("Calmar 整段與前後段都勝 0050", "calmar"),
                         ("年化落後 0050 不超過 3 個百分點", "return"), ("衛星規則勝過同批股票買進持有", "bias"),
                         ("相鄰參數多數也勝 0050", "stable")):
        logger.info("  %s：%d 組（%.0f%%）", label, sum(1 for v in c.values() if v[field]), sum(1 for v in c.values() if v[field]) / total * 100)
    winners = advantaged(scan)
    logger.info("全部通過：%d 組", len(winners))
    for version in VERSIONS:
        rows = [(k, m) for k, m in winners if c[k]["version"] == version]
        logger.info("— %s（回撤 %.0f%% 以內）：%d 組，Calmar 最高的 5 組", version, VERSIONS[version] * 100, len(rows))
        for k, m in rows[:5]:
            logger.info(_row(describe(k), m))


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-7s %(message)s", datefmt="%H:%M:%S")
    parser = argparse.ArgumentParser(description="R1-4 參數掃描")
    parser.add_argument("--summary", action="store_true", help="只讀已存的結果")
    args = parser.parse_args()
    if args.summary:
        with RESULT_PATH.open("rb") as f:
            scan = pickle.load(f)
    else:
        scan = run(universe(cache.load()))
        with RESULT_PATH.open("wb") as f:
            pickle.dump(scan, f)
    log_summary(scan)
    return 0


if __name__ == "__main__":
    sys.exit(main())
