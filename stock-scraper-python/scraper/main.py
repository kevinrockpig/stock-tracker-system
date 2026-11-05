"""執行入口：python -m scraper.main [--full]（每日排程見 scripts/run_daily.sh）

更新的股票：觀察名單＋庫存中的股票（從觀察名單移除但仍有人持有時繼續更新，庫存的價格與停利線才不會停住）。
回測、投資組合回測與交易計畫只使用觀察名單。

每檔股票：
1. 增量抓取：從資料庫最新的日期接著抓（往前重疊 OVERLAP_TRADING_DAYS 個交易日）；
   資料庫還沒有 HISTORY_START 起的完整歷史，或 Yahoo 重新調整了歷史價格時，改抓完整歷史
2. 在同一個交易（transaction）中：寫入日 K 與除權息事件 → 從資料庫讀回全部日 K 計算技術指標
   → 依指標掃描策略並同步策略訊號
3. 全部股票完成後，依最新訊號重新計算策略回測（scraper/backtest.py）
4. 投資組合回測：共用資金、最多同時持有 10 檔（scraper/portfolio.py）
5. 交易計畫：觀察中策略的模擬帳戶逐日執行並產生下一個交易日的計畫（scraper/paper.py）
6. 計算下一個交易日的參考價位（scraper/levels.py；庫存頁的除權息提醒也來自這裡）
"""

import argparse
import logging
import sys
from datetime import datetime
from zoneinfo import ZoneInfo

from sqlalchemy import create_engine
from sqlalchemy.exc import SQLAlchemyError

from scraper.backtest import log_summary, run_backtest
from scraper.config import (
    HISTORY_START,
    MAX_REQUESTED_STOCKS,
    OVERLAP_TRADING_DAYS,
    WATCHLIST,
    ConfigError,
    groups_of,
    build_database_url,
    tracked_stocks,
    env_file_permission_warning,
)
from scraper.db import (
    SchemaMismatchError,
    clear_removed_groups,
    current_symbols,
    delete_before_listing,
    delete_placeholder_rows,
    get_listed_date,
    load_split_date,
    get_history_from,
    init_schema,
    load_closes,
    load_held_stocks,
    load_requested_stocks,
    backtest_inputs,
    load_dividends,
    load_prices,
    load_share_change_dates,
    stored_frame,
    mark_history_complete,
    overlap_start,
    sync_signals,
    upsert_actions,
    upsert_indicators,
    upsert_prices,
    upsert_stock,
)
from scraper.corrections import apply_share_corrections, corrections_for
from scraper.fetcher import (
    READJUST_CHECK_DAYS,
    FetchError,
    FetchResult,
    detect_readjustment,
    fetch_daily_prices,
    to_action_records,
    to_records,
)
from scraper.indicators import compute_indicators, to_indicator_records
from scraper.levels import log_levels, market_code, run_reference_levels
from scraper.paper import log_paper, run_paper
from scraper.portfolio import log_portfolio, run_portfolio
from scraper.listing import fetch_listing_dates
from scraper.strategies import STRATEGIES, evaluate_strategies, to_signal_set

logger = logging.getLogger("scraper")
TAIPEI = ZoneInfo("Asia/Taipei")  # GitHub Actions 主機為 UTC，「今天」一律以台灣時間判斷


def fetch_with_history(engine, symbol: str, force_full: bool, listed_date=None) -> tuple[FetchResult, bool]:
    """增量抓日 K；需要時改抓完整歷史（HISTORY_START 或上市日起，取較晚者）。回傳（結果, 是否為完整歷史）。"""
    full_start = max(HISTORY_START, listed_date) if listed_date else HISTORY_START
    with engine.connect() as conn:
        history_from = get_history_from(conn, symbol)
        start = overlap_start(conn, symbol, OVERLAP_TRADING_DAYS)
    if force_full or history_from is None or history_from > HISTORY_START or start is None:
        if not force_full:
            logger.info("[%s] 資料庫尚未有 %s 起的完整歷史，改抓完整歷史", symbol, full_start)
        return fetch_daily_prices(symbol, start=full_start), True

    result = fetch_daily_prices(symbol, start=start)
    with engine.connect() as conn:
        stored = load_closes(conn, symbol, result.prices["trade_date"].head(READJUST_CHECK_DAYS))
    if reason := detect_readjustment(result.prices, stored):
        logger.warning("[%s] %s，改抓 %s 起的完整歷史", symbol, reason, HISTORY_START)
        return fetch_daily_prices(symbol, start=full_start), True
    return result, False


def already_current(targets, priced: set, leveled: set) -> bool:
    """每檔都已有今天的日 K 與參考價位（最後一個步驟），代表今天已完整更新過。"""
    return all(symbol in priced and symbol in leveled for symbol in targets)


def run(force_full: bool = False, skip_if_current: bool = False) -> int:
    if warning := env_file_permission_warning():
        logger.warning(warning)

    try:
        url = build_database_url()
    except ConfigError as exc:
        logger.error("設定錯誤：%s", exc)
        return 2

    logger.info("目標資料庫：%s / %s（帳號 %s）", url.host, url.database, url.username)
    engine = create_engine(url, pool_pre_ping=True, connect_args={"connect_timeout": 15})

    try:
        added = init_schema(engine)
    except SchemaMismatchError as exc:
        logger.error("資料表結構不一致：%s", exc)
        return 3
    except SQLAlchemyError as exc:
        logger.error("資料庫連線或建表失敗：%s", exc.__class__.__name__)
        logger.error("%s", getattr(exc, "orig", exc))
        return 3
    if added:
        logger.info("已補上新版新增的欄位：%s", "、".join(added))
    logger.info("資料表檢查完成：stocks, stock_prices, stock_actions, stock_indicators, strategy_signals")

    try:
        with engine.connect() as conn:
            held = load_held_stocks(conn)
            requested = load_requested_stocks(conn, {**WATCHLIST, **held}, MAX_REQUESTED_STOCKS)
    except SQLAlchemyError as exc:
        logger.warning("無法讀取庫存中與使用者自行加入的股票，只更新觀察名單：%s", getattr(exc, "orig", exc))
        held, requested = {}, {}
    targets = tracked_stocks(WATCHLIST, held, requested)
    if extra := [symbol for symbol in held if symbol not in WATCHLIST]:
        logger.info("庫存中不在觀察名單的股票 %d 檔，一併更新：%s", len(extra), "、".join(extra))
    if requested:
        logger.info("使用者自行加入的股票 %d 檔，一併更新：%s", len(requested), "、".join(requested))

    # 備援執行：今天已完整更新過就結束，不重抓、不重算（節省 Neon 的網路傳輸量與運算時數）；
    # 有任何一檔缺少今天的資料（例如 Yahoo 漏了某檔）時照常執行，重新抓取
    if skip_if_current:
        today = datetime.now(TAIPEI).date()
        with engine.connect() as conn:
            priced, leveled = current_symbols(conn, targets, today)
        if already_current(targets, priced, leveled):
            logger.info("今天（%s）%d 檔的日 K 與參考價位都已更新，略過這次執行", today, len(targets))
            engine.dispose()
            return 0
        logger.info("今天（%s）尚未完整更新：日 K %d / %d 檔、參考價位 %d / %d 檔，照常執行",
                    today, len(priced), len(targets), len(leveled), len(targets))

    # 上市（上櫃）日期：只保留這天起的日 K（排除興櫃期間等，見 listing.py）；無法取得時沿用資料庫中的紀錄
    try:
        listing_dates = fetch_listing_dates()
        logger.info("已取得官方上市 / 上櫃日期 %d 家", len(listing_dates))
    except Exception as exc:
        logger.warning("無法取得官方上市 / 上櫃日期，沿用資料庫中的紀錄：%s", exc)
        listing_dates = {}

    failed = []
    dates_by_symbol = {}
    latest_signals = {}  # symbol → (最新交易日, 當天符合的策略)
    preloaded = {}  # symbol → 回測輸入（db.backtest_inputs），供回測、投資組合、交易計畫共用
    for symbol, name in targets.items():
        try:
            listed = listing_dates.get(market_code(symbol))
            if listed is None and not listing_dates:
                with engine.connect() as conn:
                    listed = get_listed_date(conn, symbol)
            result, full = fetch_with_history(engine, symbol, force_full, listed)
            df, currency = result.prices, result.currency
            with engine.begin() as conn:
                upsert_stock(conn, symbol, name, currency, groups_of(symbol), listed)
                removed = delete_placeholder_rows(conn, symbol)
                counts = upsert_prices(conn, to_records(symbol, df))
                action_records = to_action_records(symbol, result.actions)
                if corrections_for(symbol):  # Yahoo 漏掉的股本事件（corrections.py）
                    action_records = apply_share_corrections(
                        symbol, action_records, load_share_change_dates(conn, symbol))
                action_counts = upsert_actions(conn, action_records)
                if listed and (before := delete_before_listing(conn, symbol, listed)):
                    logger.info("[%s] 刪除上市（上櫃）日 %s 之前的日 K %d 筆", symbol, listed, before)
                if full:
                    mark_history_complete(conn, symbol, HISTORY_START)
                # 以資料庫中的完整日 K 計算，確保指標與 stock_prices 的日期逐筆對應
                prices = load_prices(conn, symbol)
                indicators = compute_indicators(prices)
                indicator_records = to_indicator_records(symbol, indicators)
                indicator_counts = upsert_indicators(conn, indicator_records)
                # 策略、回測、投資組合、交易計畫都使用記憶體中的這份資料（與寫入後讀回相同），不再重讀完整歷史
                stored = stored_frame(prices, indicator_records)
                # 掃描全部交易日（不只最新一天），讓歷史訊號也可查詢
                strategy_result = evaluate_strategies(stored)
                signal_set = to_signal_set(strategy_result)
                signal_counts = sync_signals(conn, symbol, signal_set)
                inputs = backtest_inputs(stored, signal_set, load_dividends(conn, symbol))
            preloaded[symbol] = inputs  # 交易成功寫入後才共用
        except FetchError as exc:
            logger.error("[%s] 擷取失敗：%s", symbol, exc)
            failed.append(symbol)
            continue
        except SQLAlchemyError as exc:
            logger.error("[%s] 寫入失敗：%s", symbol, getattr(exc, "orig", exc))
            failed.append(symbol)
            continue
        except Exception:
            logger.exception("[%s] 未預期的錯誤", symbol)
            failed.append(symbol)
            continue

        # 以資料庫中的日期為準（indicators 來自資料庫完整日 K），yfinance 本次漏回傳但資料庫已有的日期不算缺漏
        dates_by_symbol[symbol] = set(indicators["trade_date"])
        latest = df.iloc[-1]
        logger.info(
            "[%s %s] %s%d 筆（%s ~ %s）新增 %d / 更新 %d / 未變動 %d / 刪除補值 %d；最新收盤 %s %s @ %s",
            symbol, name, "完整歷史 " if full else "", len(df), df["trade_date"].iloc[0], latest["trade_date"],
            counts["inserted"], counts["updated"], counts["unchanged"], removed,
            latest["close_price"], currency or "", latest["trade_date"],
        )
        if action_counts["inserted"] or action_counts["updated"]:
            logger.info(
                "[%s] 除權息事件 %d 筆 新增 %d / 更新 %d",
                symbol, len(action_records), action_counts["inserted"], action_counts["updated"],
            )
        last = indicators.iloc[-1]
        logger.info(
            "[%s] 指標 %d 筆 新增 %d / 更新 %d / 未變動 %d；%s MA5 %.2f MA20 %.2f DIF %.2f MACD %.2f RSI6 %.2f K %.2f D %.2f",
            symbol, len(indicators), indicator_counts["inserted"], indicator_counts["updated"], indicator_counts["unchanged"],
            last["trade_date"], last["ma5"], last["ma20"], last["macd_dif"], last["macd_signal"], last["rsi6"], last["kd_k"], last["kd_d"],
        )
        last_day = strategy_result.iloc[-1]
        matched = [code for code in STRATEGIES if last_day[code]]
        latest_signals[symbol] = (last_day["trade_date"], matched)
        logger.info(
            "[%s] 策略訊號 新增 %d / 刪除 %d / 未變動 %d；%s 符合：%s",
            symbol, signal_counts["inserted"], signal_counts["deleted"], signal_counts["unchanged"],
            last_day["trade_date"], "、".join(STRATEGIES[c] for c in matched) or "無",
        )

    with engine.begin() as conn:
        if removed_count := clear_removed_groups(conn, WATCHLIST):
            logger.info("已從觀察名單移除 %d 檔（資料保留，清除組別）", removed_count)
    warn_missing_dates(dates_by_symbol)
    # 策略選股結果只列觀察名單（與網站的策略選股頁相同）
    log_strategy_summary({s: v for s, v in latest_signals.items() if s in WATCHLIST})
    try:
        log_summary(run_backtest(engine, WATCHLIST, preloaded))
    except Exception:
        logger.exception("策略回測失敗（日 K、指標與訊號已寫入，不受影響）")
        failed.append("回測")
    try:
        with engine.connect() as conn:
            split_date = load_split_date(conn)
        if split_date:
            log_portfolio(run_portfolio(engine, WATCHLIST, split_date, preloaded))
    except Exception:
        logger.exception("投資組合回測失敗（其他結果已寫入，不受影響）")
        failed.append("投資組合回測")
    try:
        log_paper(run_paper(engine, WATCHLIST, preloaded))
    except Exception:
        logger.exception("交易計畫失敗（其他結果已寫入，不受影響）")
        failed.append("交易計畫")
    try:
        log_levels(run_reference_levels(engine, list(targets)))
    except Exception:
        logger.exception("參考價位計算失敗（日 K、指標、訊號與回測已寫入，不受影響）")
        failed.append("參考價位")
    engine.dispose()
    failed_symbols = [s for s in failed if s in targets]
    logger.info("完成：成功 %d 檔，失敗 %d 檔 %s", len(targets) - len(failed_symbols), len(failed_symbols), failed or "")
    return exit_code(failed, requested)


def exit_code(failed: list[str], requested) -> int:
    """有失敗回傳 1。使用者自行加入的股票（例如之後下市）抓取失敗只警告，不讓每日更新失敗、不影響後續的檢查與通知。"""
    if only_requested := [s for s in failed if s in requested]:
        logger.warning("使用者自行加入的股票抓取失敗 %d 檔（不影響結束碼）：%s", len(only_requested), "、".join(only_requested))
    return 1 if [s for s in failed if s not in requested] else 0


# 只檢查最近幾個交易日的缺漏：這段期間的偶發缺漏重新執行通常可以補上；
# 更早、每次都缺的日期是 yfinance 資料來源本身的缺漏，已列在 sql/verify.sql 查詢 8，不再每次警告
RECENT_TRADING_DAYS = 20


def warn_missing_dates(dates_by_symbol: dict[str, set]) -> None:
    """同一市場的股票交易日應一致：其他股票都有、資料庫中這檔卻沒有的近期日期，多半是 yfinance 漏資料。

    只比對該股票自己資料範圍內的日期，避免新上市股票被誤判。
    """
    all_dates = set().union(*dates_by_symbol.values())
    recent = set(sorted(all_dates)[-RECENT_TRADING_DAYS:])
    for symbol, dates in dates_by_symbol.items():
        first, last = min(dates), max(dates)
        missing = sorted(d for d in recent - dates if first <= d <= last)
        if missing:
            logger.warning(
                "[%s] 資料庫缺少最近 %d 個交易日中的 %d 天：%s（其他股票都有；可稍後重新執行補抓）",
                symbol, RECENT_TRADING_DAYS, len(missing), ", ".join(str(d) for d in missing),
            )


def log_strategy_summary(latest_signals: dict[str, tuple]) -> None:
    """列出市場最新交易日各策略符合的股票。

    最新交易日取所有股票中最晚的日期；某檔缺少這天資料時不列入（避免把前一天的訊號當成今天）。
    休市日執行時沒有新的交易日，摘要會是前一個交易日的結果，屬於正常情況。
    """
    if not latest_signals:
        return
    market_date = max(date for date, _ in latest_signals.values())
    stale = [symbol for symbol, (date, _) in latest_signals.items() if date != market_date]
    logger.info("===== %s 策略選股結果 =====", market_date)
    for code, label in STRATEGIES.items():
        symbols = [s for s, (date, matched) in latest_signals.items() if date == market_date and code in matched]
        logger.info("  %s（%s）：%s", label, code, "、".join(symbols) or "無")
    if stale:
        logger.warning("  以下股票缺少 %s 的資料，未列入本次結果：%s", market_date, "、".join(stale))


def main() -> None:
    parser = argparse.ArgumentParser(description="擷取觀察名單日行情並寫入 PostgreSQL")
    parser.add_argument("--full", action="store_true", help=f"強制重抓 {HISTORY_START} 起的完整歷史（平常不需要，程式會自動判斷）")
    parser.add_argument("--skip-if-current", action="store_true",
                        help="今天的日 K 與參考價位都已更新時直接結束（GitHub Actions 的備援執行使用）")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-7s %(message)s", datefmt="%H:%M:%S")
    sys.exit(run(args.full, args.skip_if_current))


if __name__ == "__main__":
    main()
