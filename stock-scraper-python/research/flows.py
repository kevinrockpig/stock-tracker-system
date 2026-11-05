"""R2-3 三大法人：規則、事件定義與判斷標準在看結果之前固定。

    python -m research.flows

(a) 個股訊號（法人買賣超當天收盤後公布，和價格訊號一樣在隔天開盤進場）：
- trust_streak3：投信連續 3 天買超
- foreign_10d：外資（含外資自營商）近 10 個交易日累計買超
用法同 R2-2：單獨（成立的第一天）或過濾 R1 的趨勢訊號；和 R1 同設定比較

(b) 事件研究（使用者 2026-11-05 提出）：大跌前，法人是否已經在買避險類股？
- 大跌：0050 收盤比近 20 個交易日的最高收盤低 10% 以上；同一次下跌只算一次（下一次至少相隔 60 個交易日）；
  「高點日」為該 20 日內最高收盤那天
- 避險類股買賣超：金融保險、航運、食品、油電燃氣、電信三雄（上市），外資＋投信買賣超股數 × 當天收盤價，
  取近 20 個交易日合計；和自己過去 250 個交易日比較得到百分位（只用過去的資料，0 = 最低、1 = 最高）
- 主要問題（只看這一個，避免多重比較）：高點日的避險類股百分位，平均是否明顯高於 0.5
  （以所有交易日的百分位重抽同樣個數，算出偶然達到的機率）
- 次要（只描述）：各類股分開看、全市場外資買賣超、百分位 ≥ 0.8 之後 20 / 40 天 0050 的報酬與發生大跌的比例
"""

import logging
import random
import sys
from dataclasses import replace
from datetime import date
from statistics import mean, median

from research import cache, finmind
from research.data import ETF, universe
from research.metrics import summary
from research.revenue import FREQUENCIES, TREND, plan, run, summarize
from research.simulator import simulate
from scraper.backtest import buy_and_hold_return, entry_signals
from scraper.portfolio import Stock

logger = logging.getLogger(__name__)

RULES = ["trust_streak3", "foreign_10d"]
SIGNALS = ["trend", *(f"trend&{r}" for r in RULES), *RULES]
DROP = -0.10
DROP_WINDOW = 20
EVENT_GAP = 60
FLOW_WINDOW = 20
RANK_WINDOW = 250
HIGH = 0.8
SAMPLES = 20000


# ---- (a) 個股訊號 ----

def flow_states(dates: list[date], days: dict[date, dict[str, int]]) -> dict[str, set[int]]:
    states = {r: set() for r in RULES}
    trust_run, foreign = 0, []
    for i, day in enumerate(dates):
        flow = days.get(day)
        trust_run = trust_run + 1 if flow and flow["trust"] > 0 else 0
        foreign.append(flow["foreign"] if flow else 0)
        if trust_run >= 3:
            states["trust_streak3"].add(i)
        if i + 1 >= 10 and sum(foreign[-10:]) > 0 and all(dates[k] in days for k in range(i - 9, i + 1)):
            states["foreign_10d"].add(i)
    return states


def with_flows(stocks: dict[str, Stock], flows: dict[str, dict]) -> dict[str, Stock]:
    out = {}
    for symbol, stock in stocks.items():
        trend = set().union(*(stock.entries.get(s, set()) for s in TREND))
        entries = {**stock.entries, "trend": trend}
        days = flows.get(finmind.stock_id(symbol), {})
        states = flow_states(stock.bars.dates, days) if symbol != ETF and days else {}
        for rule in RULES:
            state = states.get(rule, set())
            entries[f"trend&{rule}"] = {i for i in trend if i in state}
            entries[rule] = set(entry_signals(sorted(state), True))
        out[symbol] = Stock(stock.bars, stock.index_of, entries)
    return out


# ---- (b) 事件研究 ----

def drop_events(dates: list[date], closes: list[float]) -> list[tuple[date, date, float]]:
    """(高點日, 確認日, 從高點的跌幅)。"""
    events, last = [], -EVENT_GAP - 1
    for i in range(DROP_WINDOW - 1, len(closes)):
        window = range(i - DROP_WINDOW + 1, i + 1)
        peak = max(window, key=lambda k: closes[k])
        change = closes[i] / closes[peak] - 1
        if change <= DROP and i - last > EVENT_GAP:
            events.append((dates[peak], dates[i], change))
            last = i
    return events


def rolling_sum(values: list[float], window: int) -> list[float | None]:
    out, total = [], 0.0
    for i, v in enumerate(values):
        total += v
        if i >= window:
            total -= values[i - window]
        out.append(total if i + 1 >= window else None)
    return out


def percentiles(values: list[float | None], window: int) -> list[float | None]:
    """每天的值在自己過去 window 天（含當天）中的百分位；資料不足為 None。"""
    out = []
    for i, v in enumerate(values):
        past = [x for x in values[max(0, i - window + 1):i + 1] if x is not None]
        out.append(None if v is None or len(past) < window else sum(x <= v for x in past) / len(past) - 1 / len(past) / 2)
    return out


def sector_values(ext: finmind.External, dates: list[date], members: list[str]) -> list[float]:
    """每個交易日，類股內外資＋投信買賣超金額合計（元）。"""
    out = []
    for day in dates:
        total = 0.0
        for sid in members:
            flow, price = ext.flows.get(sid, {}).get(day), ext.prices.get(sid, {}).get(day)
            if flow and price:
                total += (flow["foreign"] + flow["trust"]) * price
        out.append(total)
    return out


def chance(observed: float, pool: list[float], count: int, seed: int = 0) -> float:
    """從 pool 隨機抽 count 個取平均，≥ observed 的機率。"""
    rng = random.Random(seed)
    return sum(mean(rng.sample(pool, count)) >= observed for _ in range(SAMPLES)) / SAMPLES


def event_study(data: cache.MarketData, ext: finmind.External) -> list[str]:
    etf = data.bars[ETF]
    first_flow = min(min(days) for days in ext.flows.values() if days)
    frame = etf[etf["trade_date"] >= first_flow]
    dates, closes = list(frame["trade_date"]), list(frame["close_price"])
    events = drop_events(dates, closes)
    index = {d: i for i, d in enumerate(dates)}
    lines = [f"期間 {dates[0]} ～ {dates[-1]}，大跌（20 日內從高點跌 ≥10%）{len(events)} 次："]

    groups = {**ext.defensive, "避險類股合計": sorted({s for v in ext.defensive.values() for s in v})}
    ranks = {name: percentiles(rolling_sum(sector_values(ext, dates, members), FLOW_WINDOW), RANK_WINDOW) for name, members in groups.items()}
    market = [(ext.market.get(d, {}).get("foreign", 0.0) + ext.market.get(d, {}).get("trust", 0.0)) for d in dates]
    ranks["全市場外資＋投信"] = percentiles(rolling_sum(market, FLOW_WINDOW), RANK_WINDOW)

    usable = [(peak, hit, change) for peak, hit, change in events if ranks["避險類股合計"][index[peak]] is not None]
    for peak, hit, change in events:
        r = ranks["避險類股合計"][index[peak]]
        m = ranks["全市場外資＋投信"][index[peak]]
        lines.append(f"  高點 {peak} → {hit} 跌 {change:.1%}；高點日百分位：避險類股 {'—' if r is None else f'{r:.2f}'}、"
                     f"全市場 {'—' if m is None else f'{m:.2f}'}")
    lines.append(f"（前 {RANK_WINDOW + FLOW_WINDOW} 個交易日資料不足，可用事件 {len(usable)} 次）")

    lines.append("主要問題：高點日的避險類股百分位平均是否明顯高於 0.5")
    for name, values in ranks.items():
        at_peak = [values[index[p]] for p, _, _ in usable if values[index[p]] is not None]
        pool = [v for v in values if v is not None]
        if not at_peak:
            continue
        observed = mean(at_peak)
        p_high = chance(observed, pool, len(at_peak))
        p_low = chance(-observed, [-v for v in pool], len(at_peak))
        tag = "（主要）" if name == "避險類股合計" else "（次要）"
        lines.append(f"  {name}{tag}：平均 {observed:.2f}（{len(at_peak)} 次）；偶然這麼高的機率 {p_high:.1%}、這麼低 {p_low:.1%}")

    lines.append(f"次要：避險類股百分位 ≥ {HIGH} 的日子之後 0050 的表現（對照所有日子）")
    values = ranks["避險類股合計"]
    hits = {d for _, d, _ in events}
    for horizon in (20, 40):
        def stats(days: list[int]) -> str:
            forward = [closes[i + horizon] / closes[i] - 1 for i in days if i + horizon < len(closes)]
            drops = [any(dates[k] in hits for k in range(i + 1, min(i + horizon, len(dates) - 1) + 1)) for i in days if i + horizon < len(closes)]
            return f"平均報酬 {mean(forward):.2%}、期間內發生大跌 {mean(drops):.1%}（{len(forward)} 天）"
        signal_days = [i for i, v in enumerate(values) if v is not None and v >= HIGH]
        all_days = [i for i, v in enumerate(values) if v is not None]
        lines.append(f"  {horizon} 天：訊號日 {stats(signal_days)}；所有日子 {stats(all_days)}")
    return lines


def lagged(flows: dict[str, dict], k: int) -> dict[str, dict]:
    """安慰劑：把每檔的法人資料往後移 k 個交易日（用舊資料）。效果若真的來自法人的時機，延後後應該消失。"""
    out = {}
    for sid, days in flows.items():
        ordered = sorted(days)
        out[sid] = {ordered[i + k]: days[ordered[i]] for i in range(len(ordered) - k)}
    return out


def robustness(data: cache.MarketData, ext: finmind.External) -> list[str]:
    """通過的訊號再檢查：延後 20 / 60 天的安慰劑、隨機排序（10 個種子的中位數）、拿掉漲最多的 5 檔。"""
    base = universe(data)
    variants = {"原始": with_flows(base, ext.flows), "延後20天": with_flows(base, lagged(ext.flows, 20)),
                "延後60天": with_flows(base, lagged(ext.flows, 60))}
    gains = sorted(((buy_and_hold_return(s.bars) or 0, sym) for sym, s in base.items() if sym != ETF), reverse=True)
    top5 = {sym for _, sym in gains[:5]}

    def calmar(stocks, p) -> dict:
        r = simulate(stocks, p)
        return summary(r.dates, r.equity)

    lines = ["穩健性（年化 / 回撤 / Calmar）："]
    for signal in ["trend", "trend&foreign_10d", "trust_streak3"]:
        for core in (0.5, 0.3):
            for frequency in FREQUENCIES:
                p = plan(signal, "pct10", core, frequency)
                parts = [f"{name} {m['cagr']:.1%}/{m['max_drawdown']:.1%}/{m['calmar']:.2f}"
                         for name, stocks in variants.items() if signal != "trend" or name == "原始"
                         for m in [calmar(stocks, p)]]
                random_rank = median(calmar(variants["原始"], replace(p, rank="random", seed=s))["calmar"] for s in range(10))
                without = calmar({k: v for k, v in variants["原始"].items() if k not in top5}, p)["calmar"]
                lines.append(f"  {signal:<18} 核心{core:.0%} {frequency:<6} " + "；".join(parts)
                             + f"；隨機排序中位 {random_rank:.2f}；拿掉前 5 檔 {without:.2f}")
    return lines


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-7s %(message)s", datefmt="%H:%M:%S")
    data, ext = cache.load(), finmind.load()
    for line in event_study(data, ext):
        logger.info(line)
    stocks = with_flows(universe(data), ext.flows)
    for line in summarize(run(stocks, SIGNALS), SIGNALS):
        logger.info(line)
    for line in robustness(data, ext):
        logger.info(line)
    return 0


if __name__ == "__main__":
    sys.exit(main())
