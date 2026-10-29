"""執行入口：python -m scraper.main [--period 2y]（每日排程見 scripts/run_daily.sh）

每檔股票在同一個交易（transaction）中完成：
寫入日 K → 從資料庫讀回全部日 K 計算技術指標 → 依指標掃描策略並同步策略訊號。
"""

import argparse
import logging
import sys

from sqlalchemy import create_engine
from sqlalchemy.exc import SQLAlchemyError

from scraper.config import DEFAULT_PERIOD, WATCHLIST, ConfigError, build_database_url, env_file_permission_warning
from scraper.db import (
    SchemaMismatchError,
    delete_placeholder_rows,
    init_schema,
    load_prices,
    load_strategy_inputs,
    sync_signals,
    upsert_indicators,
    upsert_prices,
    upsert_stock,
)
from scraper.fetcher import FetchError, fetch_daily_prices, to_records
from scraper.indicators import compute_indicators, to_indicator_records
from scraper.strategies import STRATEGIES, evaluate_strategies, to_signal_set

logger = logging.getLogger("scraper")


def run(period: str) -> int:
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
        init_schema(engine)
    except SchemaMismatchError as exc:
        logger.error("資料表結構不一致：%s", exc)
        return 3
    except SQLAlchemyError as exc:
        logger.error("資料庫連線或建表失敗：%s", exc.__class__.__name__)
        logger.error("%s", getattr(exc, "orig", exc))
        return 3
    logger.info("資料表檢查完成：stocks, stock_prices, stock_indicators, strategy_signals")

    failed = []
    dates_by_symbol = {}
    latest_signals = {}  # symbol → (最新交易日, 當天符合的策略)
    for symbol, name in WATCHLIST.items():
        try:
            df, currency = fetch_daily_prices(symbol, period)
            with engine.begin() as conn:
                upsert_stock(conn, symbol, name, currency)
                removed = delete_placeholder_rows(conn, symbol)
                counts = upsert_prices(conn, to_records(symbol, df))
                # 以資料庫中的完整日 K 計算，確保指標與 stock_prices 的日期逐筆對應
                indicators = compute_indicators(load_prices(conn, symbol))
                indicator_counts = upsert_indicators(conn, to_indicator_records(symbol, indicators))
                # 掃描全部交易日（不只最新一天），讓歷史訊號也可查詢
                strategy_result = evaluate_strategies(load_strategy_inputs(conn, symbol))
                signal_counts = sync_signals(conn, symbol, to_signal_set(strategy_result))
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
            "[%s %s] %d 筆（%s ~ %s）新增 %d / 更新 %d / 未變動 %d / 刪除補值 %d；最新收盤 %s %s @ %s",
            symbol, name, len(df), df["trade_date"].iloc[0], latest["trade_date"],
            counts["inserted"], counts["updated"], counts["unchanged"], removed,
            latest["close_price"], currency or "", latest["trade_date"],
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

    warn_missing_dates(dates_by_symbol)
    log_strategy_summary(latest_signals)
    engine.dispose()
    logger.info("完成：成功 %d 檔，失敗 %d 檔 %s", len(WATCHLIST) - len(failed), len(failed), failed or "")
    return 1 if failed else 0


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
    parser.add_argument("--period", default=DEFAULT_PERIOD, help=f"yfinance 期間，例如 5d、1mo、1y、2y（預設 {DEFAULT_PERIOD}）")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-7s %(message)s", datefmt="%H:%M:%S")
    sys.exit(run(args.period))


if __name__ == "__main__":
    main()
