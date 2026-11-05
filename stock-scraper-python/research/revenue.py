"""R2-2 月營收動能：規則與參數範圍在看結果之前固定，和 R1 的「均衡」等版本比較是否更好。

    python -m research.revenue

月營收的時間：每個交易日只用「申報期限（次月 10 日）已過」的最新月份，也就是 10 日之後的第一個交易日才算知道
（保守；多數公司更早公布，所以這裡只會低估、不會偷看未來）。

規則（以最新一個已知的月份 m 判斷）：
- rev_yoy3：最近 3 個月的營收年增率都 > 0
- rev_3m_high：近 3 個月合計的年增率 > 0，且是最近 12 個月（每月滾動計算）中最高
- rev_avg_high：近 3 個月平均營收是最近 24 個月中最高（3 個月平均可平滑季節性）
- rev_down（排除用）：近 3 個月合計的年增率 < 0
需要的月份不連續（缺資料）時規則不成立；沒有月營收的股票（例如 0050）不適用。

用法（每個規則）：
- 單獨：規則成立的第一天當作訊號（和 R1 的狀態型策略相同）
- 過濾：R1 的趨勢訊號（創 120 日新高或季線上揚時黃金交叉）出現時，規則也要成立
- 排除：R1 的趨勢訊號，但營收衰退（rev_down）時不買
"""

import argparse
import logging
import pickle
import sys
from dataclasses import replace
from datetime import date

from research import cache, finmind
from research.data import ETF, universe
from research.finmind import Revenue
from research.scan import CORE_WEIGHTS, Key, beats_calmar, evaluate_curve, plan_for
from research.simulator import benchmark_plan, simulate
from scraper.backtest import entry_signals
from scraper.portfolio import Stock

logger = logging.getLogger(__name__)

RESULT_PATH = cache.CACHE_PATH.parent / "revenue_scan.pkl"
TREND = ("high_120", "golden_cross_trend")  # R1 的共同訊號
RULES = ["rev_yoy3", "rev_3m_high", "rev_avg_high"]
SIGNALS = ["trend", *(f"trend&{r}" for r in RULES), "trend-rev_down", *RULES]  # trend = R1 原本的設定（比較基準）
EXITS = ["pct10", "pct15", "atr3"]  # pct10 為 R1 版本；其餘為相鄰參數
FREQUENCIES = ["daily", "weekly"]


# ---- 規則 ----

def _consecutive(months: list[Revenue], end: int, count: int) -> bool:
    """months[end-count+1 .. end] 是連續的月份。"""
    if end - count + 1 < 0:
        return False
    keys = [months[k].year * 12 + months[k].month for k in range(end - count + 1, end + 1)]
    return all(b - a == 1 for a, b in zip(keys, keys[1:]))


def _sum_yoy(months: list[Revenue], end: int, span: int) -> float | None:
    """以 end 為最後一個月、span 個月合計的年增率；需要 span + 12 個連續月份。"""
    if not _consecutive(months, end, span + 12):
        return None
    now = sum(months[k].revenue for k in range(end - span + 1, end + 1))
    before = sum(months[k].revenue for k in range(end - span - 11, end - 11))
    return now / before - 1 if before > 0 else None


def month_rules(months: list[Revenue]) -> list[dict[str, bool]]:
    """每個月份（以該月為最新已知月份）各規則是否成立。"""
    out = []
    for m in range(len(months)):
        yoy1 = [_sum_yoy(months, m - k, 1) for k in range(3)]
        yoy3 = _sum_yoy(months, m, 3)
        history = [_sum_yoy(months, m - k, 3) for k in range(12)]
        averages = [sum(months[k].revenue for k in range(e - 2, e + 1)) / 3 for e in range(m - 21, m + 1)] if _consecutive(months, m, 24) else None
        out.append({
            "rev_yoy3": all(v is not None and v > 0 for v in yoy1),
            "rev_3m_high": yoy3 is not None and yoy3 > 0 and all(v is not None for v in history) and yoy3 >= max(history),
            "rev_avg_high": averages is not None and averages[-1] >= max(averages),
            "rev_down": yoy3 is not None and yoy3 < 0,
        })
    return out


def daily_states(dates: list[date], months: list[Revenue]) -> dict[str, set[int]]:
    """規則 → 成立的交易日 index（只用申報期限已過的月份）。"""
    rules = month_rules(months)
    states = {name: set() for name in [*RULES, "rev_down"]}
    known = -1
    for i, day in enumerate(dates):
        while known + 1 < len(months) and months[known + 1].deadline < day:
            known += 1
        if known < 0:
            continue
        for name, ok in rules[known].items():
            if ok:
                states[name].add(i)
    return states


def with_revenue(stocks: dict[str, Stock], revenue: dict[str, list[Revenue]]) -> dict[str, Stock]:
    """在每檔股票加上本研究的訊號（entries 的新鍵）。"""
    out = {}
    for symbol, stock in stocks.items():
        trend = set().union(*(stock.entries.get(s, set()) for s in TREND))
        entries = dict(stock.entries)
        entries["trend"] = trend
        months = revenue.get(finmind.stock_id(symbol), [])
        states = daily_states(stock.bars.dates, months) if symbol != ETF and months else {}
        for rule in RULES:
            state = states.get(rule, set())
            entries[f"trend&{rule}"] = {i for i in trend if i in state}
            entries[rule] = set(entry_signals(sorted(state), True))
        entries["trend-rev_down"] = {i for i in trend if i not in states.get("rev_down", set())}
        out[symbol] = Stock(stock.bars, stock.index_of, entries)
    return out


# ---- 掃描 ----

def plan(signal: str, exit_: str, core: float, frequency: str):
    return replace(plan_for(Key("high_120", exit_, core, "risk", "no_new60", frequency)), strategies=(signal,))


def run(stocks: dict[str, Stock], signals: list[str] = SIGNALS) -> dict:
    bench = simulate(stocks, benchmark_plan())
    split = bench.dates[len(bench.dates) // 2]
    results = {}
    keys = [(s, e, c, f) for s in signals for e in EXITS for c in CORE_WEIGHTS for f in FREQUENCIES]
    for n, key in enumerate(keys, 1):
        r = simulate(stocks, plan(*key))
        results[key] = {**evaluate_curve(r.dates, r.equity, split), "trades": r.trades}
        if n % 50 == 0:
            logger.info("已完成 %d / %d", n, len(keys))
    return {"split": split, "etf": evaluate_curve(bench.dates, bench.equity, split), "results": results}


def improves(m: dict, base: dict) -> bool:
    """整段與前後兩段的 Calmar 都高於同設定的 R1。"""
    return beats_calmar(m, base)


def summarize(scan: dict, signals: list[str] = SIGNALS) -> list[str]:
    results, etf = scan["results"], scan["etf"]
    lines = [f"切分日 {scan['split']}；0050 年化 {etf['cagr']:.1%} 回撤 {etf['max_drawdown']:.1%} Calmar {etf['calmar']:.2f}"]
    header = f"{'訊號':<22}{'核心':>5}{'頻率':>8}{'年化':>8}{'回撤':>8}{'Calmar':>8}{'前段':>7}{'後段':>7}{'交易':>6}  勝過R1  相鄰也勝"
    lines.append(header)
    for core in (0.7, 0.5, 0.3):
        for frequency in FREQUENCIES:
            for signal in signals:
                m = results[(signal, "pct10", core, frequency)]
                base = results[("trend", "pct10", core, frequency)]
                neighbors = [(signal, e, core, frequency) for e in ("pct15", "atr3")]
                neighbors += [(signal, "pct10", c, frequency) for c in CORE_WEIGHTS if abs(c - core) == 0.2]
                wins = sum(improves(results[k], results[("trend", *k[1:])]) for k in neighbors)
                mark = "—" if signal == "trend" else ("是" if improves(m, base) else "否")
                lines.append(f"{signal:<22}{core:>5.0%}{frequency:>8}{m['cagr']:>8.1%}{m['max_drawdown']:>8.1%}{m['calmar']:>8.2f}"
                             f"{m['calmar_first']:>7.2f}{m['calmar_second']:>7.2f}{m['trades']:>6}  {mark:<6}  "
                             f"{'—' if signal == 'trend' else f'{wins}/{len(neighbors)}'}")
            lines.append("")
    return lines


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-7s %(message)s", datefmt="%H:%M:%S")
    parser = argparse.ArgumentParser(description="R2-2 月營收動能")
    parser.add_argument("--summary", action="store_true", help="只讀已存的結果")
    args = parser.parse_args()
    if args.summary:
        with RESULT_PATH.open("rb") as f:
            scan = pickle.load(f)
    else:
        stocks = with_revenue(universe(cache.load()), finmind.load().revenue)
        scan = run(stocks)
        with RESULT_PATH.open("wb") as f:
            pickle.dump(scan, f)
    for line in summarize(scan):
        logger.info(line)
    return 0


if __name__ == "__main__":
    sys.exit(main())
