"""策略回測：依 strategy_signals 的歷史訊號模擬進出場，產生交易明細與統計（寫入 backtest_trades / backtest_stats）。

執行：python -m scraper.backtest（每日 scraper.main 結束時也會自動執行）

規則固定、不做參數最佳化（只有 4 檔、約 11 年的資料，自動挑出的「最佳參數」多半是巧合）：
- 進場：訊號是用收盤價算的，收盤後才知道 → 下一個交易日開盤價買進。
  開盤漲幅已達 LIMIT_UP_SKIP 以上（接近漲停）視為買不到，略過這次訊號
- 出場：EXIT_RULES，各自獨立統計（持有 N 個交易日於收盤賣出；或移動停利）
- 同一股票、同一策略、同一出場規則同時只持有一筆：持有期間出現的新訊號不進場
- 狀態型策略（strategies.STATE_STRATEGIES）只把「進入狀態的第一天」當作訊號
- 成本：一般台股費率，買賣手續費各 0.1425%（不打折），賣出證交稅股票 0.3%、ETF 0.1%
- 報酬含現金股利：除息日在「進場日之後、出場日當天或之前」的股利計入（進場當天才除息則已領不到）
- 基準：同一檔股票「任意一個交易日」都依相同規則進場的平均結果（可重疊）。
  超額報酬 = 每筆交易報酬 − 同一檔股票的基準平均。「有優勢」（has_edge）需同時符合：
  1. 超額報酬的 95% 信賴區間下限 > 0
  2. 穩健性：回測期間以交易日中點切成前後兩段，兩段的超額報酬（各自與同一段的基準比較）都 > 0。
     真正有效的訊號在不同時期都應有效；只在某段行情有效的，多半是剛好遇到那段行情
  不同股票常同時漲跌，合併統計的樣本並非完全獨立；同時比較多組（策略 × 出場規則）也可能有組別靠運氣通過，
  因此「有優勢」只是最低門檻，不代表未來一定有效

已知限制（也顯示在網站說明頁）：
- 假設一定能以開盤價 / 收盤價成交；未考慮跌停賣不掉、滑價
- 觀察名單是事後挑選的大型股，結果可能偏樂觀
- yfinance 少數交易日缺漏（見 sql/verify.sql 查詢 8）；鴻海 2018 年減資退還的股款未計入（查詢 23）
"""

import argparse
import logging
import math
import statistics
import sys
from dataclasses import dataclass
from datetime import date
from decimal import Decimal

import pandas as pd
from sqlalchemy import create_engine
from sqlalchemy.exc import SQLAlchemyError

from scraper.config import WATCHLIST, ConfigError, build_database_url
from scraper.db import (
    STAT_VALUE_COLUMNS,
    SchemaMismatchError,
    init_schema,
    load_backtest_inputs,
    sync_backtest_stats,
    sync_backtest_trades,
)
from scraper.strategies import STATE_STRATEGIES, STRATEGIES

logger = logging.getLogger(__name__)

BUY_FEE = 0.001425
SELL_FEE = 0.001425
SELL_TAX_STOCK = 0.003
SELL_TAX_ETF = 0.001
LIMIT_UP_SKIP = 0.095  # 漲跌幅上限 10%，考慮升降單位的進位，9.5% 以上視為已漲停
TRAILING_ATR_MULTIPLE = 3.0
TRAILING_MAX_DAYS = 60


@dataclass(frozen=True)
class ExitRule:
    label: str
    hold_days: int | None = None  # 持有 N 個交易日（含進場日），第 N 天收盤賣出
    trailing_atr: float | None = None  # 移動停利：進場後最高收盤價 − 倍數 × ATR(14)；收盤跌破則隔日開盤賣出
    max_days: int | None = None  # 移動停利最長持有天數，到期於收盤賣出


EXIT_RULES = {
    "hold_5": ExitRule("持有 5 日", hold_days=5),
    "hold_10": ExitRule("持有 10 日", hold_days=10),
    "hold_20": ExitRule("持有 20 日", hold_days=20),
    "trailing_atr": ExitRule(
        f"移動停利（{TRAILING_ATR_MULTIPLE:g} × ATR，最長 {TRAILING_MAX_DAYS} 日）",
        trailing_atr=TRAILING_ATR_MULTIPLE, max_days=TRAILING_MAX_DAYS,
    ),
}


@dataclass
class Bars:
    """單一股票的日 K（依日期由舊到新），價格為 Yahoo 依配股 / 減資 / 分割換算後的數值。"""
    dates: list[date]
    open: list[float]
    close: list[float]
    atr: list[float]  # 暖身期為 NaN
    dividends: dict  # 除息日 → 每股現金股利（與價格同一基準）
    is_etf: bool
    start: int  # 回測起點：指標暖身完成、所有策略都能判斷的第一天


@dataclass
class Trade:
    signal_date: date
    entry_date: date
    entry_price: float
    exit_date: date | None  # None：仍持有中（資料到最新一天還沒出場）
    exit_price: float | None
    exit_reason: str | None  # hold / trailing / max_hold
    dividends: float
    holding_days: int
    net_return: float | None


def is_etf(symbol: str) -> bool:
    """台股 ETF 代號以 00 開頭（例如 0050、00878）。"""
    return symbol.split(".")[0].startswith("00")


def net_return(entry_price: float, exit_price: float, dividends: float, etf: bool) -> float:
    """扣除手續費與證交稅、加上股利後的報酬率。"""
    sell_cost = SELL_FEE + (SELL_TAX_ETF if etf else SELL_TAX_STOCK)
    return (exit_price * (1 - sell_cost) + dividends) / (entry_price * (1 + BUY_FEE)) - 1


def opens_at_limit_up(bars: Bars, i: int) -> bool:
    """第 i 天開盤是否已接近漲停。參考價為前一日收盤；當天除息時要先扣掉股利。"""
    reference = bars.close[i - 1] - bars.dividends.get(bars.dates[i], 0.0)
    return reference > 0 and bars.open[i] / reference - 1 >= LIMIT_UP_SKIP


def simulate(bars: Bars, signal_index: int, rule: ExitRule) -> Trade | None:
    """訊號出現在 signal_index 那天；回傳交易結果，買不到（隔天沒有資料或開盤漲停）時回傳 None。"""
    entry = signal_index + 1
    if entry >= len(bars.dates) or opens_at_limit_up(bars, entry):
        return None

    exit_index, exit_price, reason = None, None, None
    last = len(bars.dates) - 1
    if rule.hold_days:
        if entry + rule.hold_days - 1 <= last:
            exit_index = entry + rule.hold_days - 1
            exit_price, reason = bars.close[exit_index], "hold"
    else:
        highest = -math.inf
        for i in range(entry, last + 1):
            highest = max(highest, bars.close[i])
            if i - entry + 1 >= rule.max_days:
                exit_index, exit_price, reason = i, bars.close[i], "max_hold"
                break
            atr = bars.atr[i]
            if not math.isnan(atr) and bars.close[i] < highest - rule.trailing_atr * atr:
                if i + 1 <= last:
                    exit_index, exit_price, reason = i + 1, bars.open[i + 1], "trailing"
                break  # 最新一天才跌破：明天開盤才會賣出，目前仍持有

    entry_date = bars.dates[entry]
    if exit_index is None:
        return Trade(bars.dates[signal_index], entry_date, bars.open[entry], None, None, None, 0.0, last - entry + 1, None)

    exit_date = bars.dates[exit_index]
    dividends = sum(amount for day, amount in bars.dividends.items() if entry_date < day <= exit_date)
    return Trade(
        bars.dates[signal_index], entry_date, bars.open[entry], exit_date, exit_price, reason,
        dividends, exit_index - entry + 1, net_return(bars.open[entry], exit_price, dividends, bars.is_etf),
    )


def entry_signals(signal_indexes: list[int], state: bool) -> list[int]:
    """狀態型策略只保留「前一個交易日不符合」的那天（進入狀態的第一天）。"""
    present = set(signal_indexes)
    return [i for i in sorted(signal_indexes) if not state or i - 1 not in present]


def run_strategy(bars: Bars, signal_indexes: list[int], strategy: str, rule: ExitRule) -> list[Trade]:
    """依時間順序逐一模擬；前一筆尚未出場前的訊號不進場。"""
    trades = []
    busy_until = -1  # 前一筆出場那天的 index；仍持有中為無限大
    index_of = {day: i for i, day in enumerate(bars.dates)}
    for i in entry_signals(signal_indexes, strategy in STATE_STRATEGIES):
        if i < bars.start or i + 1 <= busy_until:
            continue
        trade = simulate(bars, i, rule)
        if trade is None:
            continue
        trades.append(trade)
        busy_until = math.inf if trade.exit_date is None else index_of[trade.exit_date]
    return trades


def baseline_returns(bars: Bars, rule: ExitRule) -> list[tuple[date, float]]:
    """任意一天都以相同規則進場（可重疊）的（進場日, 報酬），代表「不看訊號」的表現。"""
    returns = []
    for i in range(bars.start, len(bars.dates) - 1):
        trade = simulate(bars, i, rule)
        if trade is not None and trade.net_return is not None:
            returns.append((trade.entry_date, trade.net_return))
    return returns


def buy_and_hold_return(bars: Bars) -> float | None:
    """回測起點隔天開盤買進、持有到最新一天收盤（含股利、扣成本）。"""
    entry = bars.start + 1
    if entry >= len(bars.dates):
        return None
    entry_date, last_date = bars.dates[entry], bars.dates[-1]
    dividends = sum(amount for day, amount in bars.dividends.items() if entry_date < day <= last_date)
    return net_return(bars.open[entry], bars.close[-1], dividends, bars.is_etf)


def wilson_interval(wins: int, n: int, z: float = 1.96) -> tuple[float, float]:
    """勝率的 95% 信賴區間（Wilson 法；樣本少或勝率接近 0 / 100% 時比常態近似可靠）。"""
    if n == 0:
        return (math.nan, math.nan)
    p = wins / n
    center = (p + z * z / (2 * n)) / (1 + z * z / n)
    margin = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / (1 + z * z / n)
    return (center - margin, center + margin)


def t_critical(df: int) -> float:
    """t 分布雙尾 95% 臨界值（Cornish-Fisher 近似，自由度 3 以上誤差小於 0.01，不需額外套件）。"""
    z = 1.959964
    return z + (z**3 + z) / (4 * df) + (5 * z**5 + 16 * z**3 + 3 * z) / (96 * df**2)


def _mean_or_none(values) -> float | None:
    values = list(values)
    return statistics.fmean(values) if values else None


def split_excess(closed: list[tuple[Trade, str]], baselines: dict, split_date: date) -> dict:
    """前後兩段各自的超額報酬：進場日在 split_date 之前為前段；每段都與同一檔股票「同一段」的任意日進場比較。"""
    result = {}
    for half, in_half in (("first", lambda d: d < split_date), ("second", lambda d: d >= split_date)):
        base = {s: _mean_or_none(r for d, r in items if in_half(d)) for s, items in baselines.items()}
        excess = [t.net_return - base[s] for t, s in closed if in_half(t.entry_date) and base[s] is not None]
        result[f"trades_{half}"] = len(excess)
        result[f"excess_{half}"] = _mean_or_none(excess)
    return result


def summarize(trades_by_symbol: dict[str, list[Trade]], baselines: dict[str, list], split_date: date | None = None) -> dict:
    """交易統計。trades_by_symbol：股票 → 交易；baselines：股票 → 任意日進場的（進場日, 報酬），同一出場規則。

    基準與超額報酬都和「同一檔股票」比較：合併統計時，訊號集中在哪幾檔不會影響比較的公平性。
    split_date 有值時另外計算前後兩段的超額報酬，並作為「有優勢」的穩健性條件。
    """
    base = {
        s: (statistics.fmean(r for _, r in items), sum(r > 0 for _, r in items) / len(items))
        for s, items in baselines.items() if items
    }
    pairs = sorted(((t, s) for s, items in trades_by_symbol.items() for t in items), key=lambda p: p[0].entry_date)
    closed = [(t, s) for t, s in pairs if t.net_return is not None]
    returns = [t.net_return for t, _ in closed]
    wins = [r for r in returns if r > 0]
    losses = [r for r in returns if r <= 0]
    low, high = wilson_interval(len(wins), len(returns))

    streak = longest = 0
    for r in returns:
        streak = streak + 1 if r <= 0 else 0
        longest = max(longest, streak)

    excess = [t.net_return - base[s][0] for t, s in closed]
    excess_low = excess_high = None
    if len(excess) >= 2:
        half = t_critical(len(excess) - 1) * statistics.stdev(excess) / math.sqrt(len(excess))
        excess_low, excess_high = statistics.fmean(excess) - half, statistics.fmean(excess) + half

    all_baseline = [r for items in baselines.values() for _, r in items]
    halves = split_excess(closed, baselines, split_date) if split_date else {}
    robust = not split_date or all((halves[k] or 0) > 0 for k in ("excess_first", "excess_second"))
    avg_win = statistics.fmean(wins) if wins else None
    avg_loss = statistics.fmean(losses) if losses else None
    return {
        "trades": len(closed),
        "open_trades": len(pairs) - len(closed),
        "wins": len(wins),
        "win_rate": len(wins) / len(returns) if returns else None,
        "win_rate_low": None if math.isnan(low) else low,
        "win_rate_high": None if math.isnan(high) else high,
        "avg_return": statistics.fmean(returns) if returns else None,
        "median_return": statistics.median(returns) if returns else None,
        "avg_win": avg_win,
        "avg_loss": avg_loss,
        "payoff_ratio": avg_win / -avg_loss if avg_win is not None and avg_loss else None,
        "profit_factor": sum(wins) / -sum(losses) if wins and losses and sum(losses) < 0 else None,
        "max_loss": min(returns) if returns else None,
        "max_consecutive_losses": longest,
        "avg_holding_days": statistics.fmean(t.holding_days for t, _ in closed) if closed else None,
        "baseline_trades": len(all_baseline),
        # 有交易時依交易所屬股票加權（與超額報酬一致：平均報酬 − 基準平均 = 超額報酬）；沒有交易時為全部基準的平均
        "baseline_avg_return": (
            statistics.fmean(base[s][0] for _, s in closed) if closed
            else statistics.fmean(all_baseline) if all_baseline else None
        ),
        "baseline_win_rate": (
            statistics.fmean(base[s][1] for _, s in closed) if closed
            else sum(r > 0 for r in all_baseline) / len(all_baseline) if all_baseline else None
        ),
        "excess_return": statistics.fmean(excess) if excess else None,
        "excess_low": excess_low,
        "excess_high": excess_high,
        "split_date": split_date,
        "trades_first": halves.get("trades_first"),
        "excess_first": halves.get("excess_first"),
        "trades_second": halves.get("trades_second"),
        "excess_second": halves.get("excess_second"),
        "has_edge": excess_low is not None and excess_low > 0 and robust,
    }


def make_bars(symbol: str, frame: pd.DataFrame, dividends: dict) -> Bars:
    """frame 欄位：trade_date, open_price, close_price, atr14, ma60, macd_signal（依日期由舊到新）。"""
    values = frame[["open_price", "close_price", "atr14", "ma60", "macd_signal"]].astype(float)
    ready = values[["atr14", "ma60", "macd_signal"]].notna().all(axis=1).to_numpy()
    start = int(ready.argmax()) if ready.any() else len(frame)
    return Bars(
        dates=list(frame["trade_date"]),
        open=values["open_price"].tolist(),
        close=values["close_price"].tolist(),
        atr=values["atr14"].tolist(),
        dividends={day: float(amount) for day, amount in dividends.items()},
        is_etf=is_etf(symbol),
        start=start,
    )


@dataclass
class SymbolResult:
    bars: Bars
    trades: dict  # (strategy, rule_code) → list[Trade]
    baselines: dict  # rule_code → list[float]


def backtest_symbol(symbol: str, frame: pd.DataFrame, signals: dict, dividends: dict) -> SymbolResult:
    """signals：strategy → 符合的日期集合。"""
    bars = make_bars(symbol, frame, dividends)
    index_of = {day: i for i, day in enumerate(bars.dates)}
    trades = {}
    for strategy in STRATEGIES:
        indexes = [index_of[d] for d in signals.get(strategy, ()) if d in index_of]
        for code, rule in EXIT_RULES.items():
            trades[(strategy, code)] = run_strategy(bars, indexes, strategy, rule)
    baselines = {code: baseline_returns(bars, rule) for code, rule in EXIT_RULES.items()}
    return SymbolResult(bars, trades, baselines)


ALL_SCOPE = "ALL"  # 觀察名單全部股票合併統計


def middle_trading_date(results: dict[str, SymbolResult]) -> date | None:
    """回測期間（所有股票的交易日聯集）的中點，作為前後兩段的切分日；所有統計共用同一天。"""
    days = sorted({d for r in results.values() for d in r.bars.dates[r.bars.start:]})
    return days[len(days) // 2] if days else None


def build_stats(results: dict[str, SymbolResult]) -> list[dict]:
    """每個（策略, 範圍, 出場規則）一筆統計；範圍為個別股票，以及全部合併（ALL）。"""
    split_date = middle_trading_date(results)
    rows = []
    for strategy in STRATEGIES:
        for code in EXIT_RULES:
            scopes = {symbol: [symbol] for symbol in results}
            scopes[ALL_SCOPE] = list(results)
            for scope, symbols in scopes.items():
                trades = {s: results[s].trades[(strategy, code)] for s in symbols}
                baselines = {s: results[s].baselines[code] for s in symbols}
                holds = [r for s in symbols if (r := buy_and_hold_return(results[s].bars)) is not None]
                usable = [results[s].bars for s in symbols if results[s].bars.start < len(results[s].bars.dates)]
                rows.append({
                    "strategy": strategy,
                    "scope": scope,
                    "exit_rule": code,
                    "period_start": min((b.dates[b.start] for b in usable), default=None),
                    "period_end": max((b.dates[-1] for b in usable), default=None),
                    **summarize(trades, baselines, split_date),
                    # 合併範圍為各股票買進持有報酬的平均（等權重）
                    "buy_hold_return": statistics.fmean(holds) if holds else None,
                })
    return rows


# ---------- 讀寫資料庫與執行 ----------

def _decimal(value, places: int):
    if value is None or (isinstance(value, float) and math.isnan(value)):
        return None
    return Decimal(str(round(float(value), places)))


def to_trade_records(symbol: str, trades: dict) -> list[dict]:
    return [
        {
            "strategy": strategy, "symbol": symbol, "exit_rule": code,
            "signal_date": t.signal_date, "entry_date": t.entry_date, "entry_price": _decimal(t.entry_price, 4),
            "exit_date": t.exit_date, "exit_price": _decimal(t.exit_price, 4), "exit_reason": t.exit_reason,
            "dividends": _decimal(t.dividends, 4), "holding_days": t.holding_days, "net_return": _decimal(t.net_return, 6),
        }
        for (strategy, code), items in trades.items()
        for t in items
    ]


def to_stat_records(rows: list[dict]) -> list[dict]:
    records = []
    for row in rows:
        record = {"strategy": row["strategy"], "scope": row["scope"], "exit_rule": row["exit_rule"]}
        for name in STAT_VALUE_COLUMNS:
            value = row[name]
            # 日期與次數原樣寫入，比率四捨五入到小數 6 位
            record[name] = value if isinstance(value, (date, int)) or value is None else _decimal(value, 6)
        records.append(record)
    return records


def run_backtest(engine, symbols) -> list[dict]:
    """回測 symbols 並寫入資料庫，回傳統計列（供 log 摘要）。所有寫入在同一個交易中完成。"""
    with engine.begin() as conn:
        results = {}
        for symbol in symbols:
            frame, signals, dividends = load_backtest_inputs(conn, symbol)
            if frame.empty:
                continue
            results[symbol] = backtest_symbol(symbol, frame, signals, dividends)
            counts = sync_backtest_trades(conn, symbol, to_trade_records(symbol, results[symbol].trades))
            logger.info(
                "[%s] 回測交易 新增 %d / 更新 %d / 未變動 %d / 刪除 %d",
                symbol, counts["inserted"], counts["updated"], counts["unchanged"], counts["deleted"],
            )
        stats = build_stats(results)
        sync_backtest_stats(conn, to_stat_records(stats))
    return stats


def _pct(value) -> str:
    return "—" if value is None else f"{value * 100:+.2f}%"


def log_summary(stats: list[dict]) -> None:
    """log 中列出全部股票合併的結果：交易次數、勝率、平均報酬、相對任意日進場的超額報酬（95% 區間）與是否有優勢。"""
    rows = [r for r in stats if r["scope"] == ALL_SCOPE]
    if not rows:
        return
    logger.info(
        "===== 策略回測（%s ~ %s，前後段以 %s 切分，全部股票合併，已扣成本含股利；同時比較 %d 組）=====",
        rows[0]["period_start"], rows[0]["period_end"], rows[0]["split_date"], len(rows),
    )
    for r in rows:
        win = "—" if r["win_rate"] is None else (
            f"{r['win_rate'] * 100:.0f}%（{r['win_rate_low'] * 100:.0f}~{r['win_rate_high'] * 100:.0f}%）"
        )
        excess = "—" if r["excess_low"] is None else (
            f"{_pct(r['excess_return'])}（{_pct(r['excess_low'])} ~ {_pct(r['excess_high'])}）"
        )
        logger.info(
            "  %-8s %-24s %4d 次 勝率 %-14s 平均 %-8s 基準 %-8s 超額 %s 前段 %s 後段 %s %s",
            STRATEGIES[r["strategy"]], EXIT_RULES[r["exit_rule"]].label, r["trades"], win,
            _pct(r["avg_return"]), _pct(r["baseline_avg_return"]), excess,
            _pct(r["excess_first"]), _pct(r["excess_second"]), "★有優勢" if r["has_edge"] else "無明顯優勢",
        )


def main() -> None:
    argparse.ArgumentParser(description="依資料庫中的歷史訊號重新計算策略回測").parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-7s %(message)s", datefmt="%H:%M:%S")
    try:
        engine = create_engine(build_database_url(), pool_pre_ping=True, connect_args={"connect_timeout": 15})
        init_schema(engine)
        log_summary(run_backtest(engine, WATCHLIST))
    except (ConfigError, SchemaMismatchError) as exc:
        logger.error("設定或資料表錯誤：%s", exc)
        sys.exit(2)
    except SQLAlchemyError as exc:
        logger.error("資料庫錯誤：%s", getattr(exc, "orig", exc))
        sys.exit(3)
    engine.dispose()


if __name__ == "__main__":
    main()
