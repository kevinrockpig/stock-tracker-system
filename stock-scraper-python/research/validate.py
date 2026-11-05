"""驗證研究工具：新的模擬器在舊條件下必須與 scraper.portfolio 完全一致，重新計算的訊號必須與資料庫相同。

    python -m research.validate            # 全部策略 × 出場規則 × 亂數種子
    python -m research.validate --quick    # 每組只比對第一個亂數種子
"""

import argparse
import logging
import sys
import time

from research import cache
from research.data import ETF, computed_signals, stored_signals, universe
from research.simulator import benchmark_plan, legacy_plan, simulate
from scraper.backtest import buy_and_hold_curve, rules_for
from scraper.portfolio import SEEDS, simulate_portfolio
from scraper.strategies import STRATEGIES

logger = logging.getLogger(__name__)
TOLERANCE = 1e-9


def check_signals(data) -> list[str]:
    problems = []
    for symbol, frame in data.bars.items():
        stored, computed = stored_signals(data, symbol), computed_signals(frame)
        for code in STRATEGIES:
            if stored.get(code, set()) != computed.get(code, set()):
                diff = stored.get(code, set()) ^ computed.get(code, set())
                problems.append(f"{symbol} {code}：{len(diff)} 天不同（例如 {sorted(diff)[:3]}）")
    return problems


def check_legacy(stocks, seeds) -> list[str]:
    problems = []
    for strategy in STRATEGIES:
        for code in rules_for(strategy):
            for seed in seeds:
                old = simulate_portfolio(stocks, strategy, code, seed)
                new = simulate(stocks, legacy_plan(strategy, code, seed))
                gap = max((abs(a - b) for a, b in zip(old["equity"], new.equity)), default=0.0)
                if old["dates"] != new.dates or gap > TOLERANCE or (old["trades"], old["skipped"]) != (new.trades, new.skipped):
                    problems.append(
                        f"{strategy} × {code} 種子 {seed}：資金最大差 {gap:.2e}，交易 {old['trades']}/{new.trades}，"
                        f"略過 {old['skipped']}/{new.skipped}，日期{'相同' if old['dates'] == new.dates else '不同'}"
                    )
    return problems


def check_benchmark(stocks) -> list[str]:
    etf = stocks[ETF]
    old = buy_and_hold_curve(etf.bars)
    new = simulate(stocks, benchmark_plan(1.0, whole_shares=False))
    gap = max(abs(a - b) for a, b in zip(old, new.equity))
    same_length = len(old) == len(new.equity)
    return [] if same_length and gap <= TOLERANCE else [f"0050 買進持有：長度 {len(old)}/{len(new.equity)}，最大差 {gap:.2e}"]


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-7s %(message)s", datefmt="%H:%M:%S")
    parser = argparse.ArgumentParser(description="驗證研究用的模擬器與訊號")
    parser.add_argument("--quick", action="store_true", help="每組只比對第一個亂數種子")
    args = parser.parse_args()
    data = cache.load()
    stocks = universe(data)
    seeds = [0] if args.quick else list(range(SEEDS))
    ok = True
    for title, run in (
        ("重新計算的訊號與資料庫相同", lambda: check_signals(data)),
        ("0050 買進持有與舊版相同", lambda: check_benchmark(stocks)),
        (f"新模擬器在舊條件下與舊版相同（{len(seeds)} 個種子）", lambda: check_legacy(stocks, seeds)),
    ):
        started = time.perf_counter()
        problems = run()
        seconds = time.perf_counter() - started
        if problems:
            ok = False
            logger.error("✗ %s：%d 項不符（%.0f 秒）", title, len(problems), seconds)
            for line in problems[:10]:
                logger.error("    %s", line)
        else:
            logger.info("✓ %s（%.0f 秒）", title, seconds)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
