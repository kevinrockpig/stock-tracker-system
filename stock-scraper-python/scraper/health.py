"""每日資料健康檢查（唯讀）：python -m scraper.health

每日更新後執行（scripts/run_daily.sh 第 3 步），檢查資料來源的問題，例如 yfinance 漏抓當天、價格未調整、
停牌期間沒有股數變動紀錄（0050 1 拆 4 就是這類問題）。查詢與 sql/verify.sql 中「應回傳 0 列」的項目相同，
只挑資料來源相關、執行快速的幾項；回測與參考價位的獨立驗算在修改計算程式後再手動執行 verify.sql。

exit code：0 全部正常、1 有異常（細節寫在 log）、2 設定錯誤、3 資料庫錯誤
"""

import argparse
import logging
import sys
from dataclasses import dataclass, field
from datetime import date

from sqlalchemy import create_engine, text
from sqlalchemy.exc import SQLAlchemyError

from scraper.config import ConfigError, build_database_url
from scraper.indicators import WARMUP_BARS

logger = logging.getLogger("scraper.health")

# 市場交易日：當天有 20 檔以上有日 K（與 verify.sql 查詢 27 相同），避免把只有少數股票的日期當成交易日
_MARKET_DAYS = "SELECT trade_date FROM stock_prices GROUP BY trade_date HAVING COUNT(*) >= 20"

# 每日更新的股票：觀察名單（有組別）＋庫存中的股票（只讀代號）
_TRACKED = "SELECT symbol FROM stocks WHERE group_codes IS NOT NULL UNION SELECT symbol FROM holdings"

DATABASE_LIMIT_BYTES = 1024 ** 3  # Neon 免費方案每個 project 1 GB
DATABASE_WARN_RATIO = 0.8
SAMPLE_ROWS = 5


@dataclass(frozen=True)
class Check:
    title: str
    sql: str
    hint: str
    # 已查證、不需處理的例外：（股票代號, 日期）
    known: frozenset = field(default_factory=frozenset)


CHECKS = (
    Check(
        "yfinance 最新交易日落後官方資料",
        f"""
        SELECT o.latest AS official_latest, p.latest AS yfinance_latest
        FROM (SELECT MAX(trade_date) AS latest FROM official_daily_quotes) o,
             (SELECT MAX(trade_date) AS latest FROM ({_MARKET_DAYS}) m) p
        WHERE o.latest > p.latest
        """,
        "Yahoo 可能還沒有當天資料，稍後重新執行通常可以補上",
    ),
    Check(
        "股票缺少市場最新交易日",
        f"""
        WITH latest AS (SELECT MAX(trade_date) AS d FROM ({_MARKET_DAYS}) m)
        SELECT t.symbol, MAX(p.trade_date) AS last_date
        FROM ({_TRACKED}) t
        LEFT JOIN stock_prices p ON p.symbol = t.symbol
        GROUP BY t.symbol
        HAVING MAX(p.trade_date) IS NULL OR MAX(p.trade_date) < (SELECT d FROM latest)
        ORDER BY t.symbol
        """,
        "停牌時屬正常；否則多半是 yfinance 漏資料，稍後重新執行",
    ),
    Check(
        "休市日補值資料（verify.sql 查詢 6）",
        """
        SELECT symbol, trade_date FROM stock_prices
        WHERE volume = 0 AND open_price = close_price AND high_price = close_price AND low_price = close_price
        ORDER BY symbol, trade_date
        """,
        "應已在寫入前排除，出現代表排除條件需要調整",
    ),
    Check(
        "開高低收不合理（verify.sql 查詢 7）",
        """
        SELECT symbol, trade_date FROM stock_prices
        WHERE high_price < GREATEST(open_price, close_price) OR low_price > LEAST(open_price, close_price)
        ORDER BY symbol, trade_date
        """,
        "資料來源錯誤",
    ),
    Check(
        "指標與日 K 筆數不一致（verify.sql 查詢 9）",
        """
        SELECT p.symbol, COUNT(p.id) AS price_rows, COUNT(i.id) AS indicator_rows
        FROM stock_prices p
        LEFT JOIN stock_indicators i ON i.symbol = p.symbol AND i.trade_date = p.trade_date
        GROUP BY p.symbol
        HAVING COUNT(p.id) <> COUNT(i.id)
        ORDER BY p.symbol
        """,
        "該檔這次更新可能中途失敗，重新執行",
    ),
    Check(
        "RSI、K、D 超出 0~100（verify.sql 查詢 11）",
        """
        SELECT symbol, trade_date FROM stock_indicators
        WHERE rsi6 NOT BETWEEN 0 AND 100 OR rsi12 NOT BETWEEN 0 AND 100
           OR kd_k NOT BETWEEN 0 AND 100 OR kd_d NOT BETWEEN 0 AND 100
        ORDER BY symbol, trade_date
        """,
        "指標計算錯誤",
    ),
    Check(
        "暖身期之後仍為 NULL 的指標（verify.sql 查詢 12）",
        # 每檔前 WARMUP_BARS 筆不輸出遞迴型指標（indicators.py），新上市股票在這段期間為 NULL 屬正常
        f"""
        SELECT symbol, MIN(trade_date) AS first_date, COUNT(*) AS days
        FROM (
            SELECT symbol, trade_date, ma60, macd_dif, rsi12, kd_d,
                   ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY trade_date) AS n
            FROM stock_indicators
        ) t
        WHERE n > {WARMUP_BARS} AND (ma60 IS NULL OR macd_dif IS NULL OR rsi12 IS NULL OR kd_d IS NULL)
        GROUP BY symbol
        ORDER BY symbol
        """,
        "指標計算或寫入不完整，重新執行該檔",
    ),
    Check(
        "單日漲跌超過 10%（verify.sql 查詢 23）",
        """
        SELECT symbol, trade_date,
               ROUND((close_price / (prev_close - COALESCE(cash_dividend, 0)) - 1) * 100, 2) AS change_pct
        FROM (
            SELECT p.symbol, p.trade_date, p.close_price, a.cash_dividend,
                   LAG(p.close_price) OVER (PARTITION BY p.symbol ORDER BY p.trade_date) AS prev_close
            FROM stock_prices p
            LEFT JOIN stock_actions a ON a.symbol = p.symbol AND a.ex_date = p.trade_date
        ) t
        WHERE prev_close IS NOT NULL
          AND ABS(close_price / (prev_close - COALESCE(cash_dividend, 0)) - 1) > 0.1005
        ORDER BY symbol, trade_date
        """,
        "除權息或股本變動未調整、或資料錯誤；查證後處理或加入已知例外",
        # 原因見 verify.sql 查詢 23 的註解
        known=frozenset({
            ("2317.TW", date(2018, 10, 26)), ("2603.TW", date(2022, 9, 19)), ("2360.TW", date(2025, 8, 4)),
            ("2609.TW", date(2017, 2, 20)), ("2609.TW", date(2021, 4, 7)), ("3443.TW", date(2017, 10, 2)),
        }),
    ),
    Check(
        "停牌期間沒有股數變動紀錄（verify.sql 查詢 27）",
        f"""
        WITH market AS (
            SELECT trade_date, ROW_NUMBER() OVER (ORDER BY trade_date) AS n FROM ({_MARKET_DAYS}) m
        ), gaps AS (
            SELECT p.symbol, p.trade_date, m.n,
                   LAG(p.trade_date) OVER w AS prev_date, LAG(m.n) OVER w AS prev_n
            FROM stock_prices p JOIN market m USING (trade_date)
            WINDOW w AS (PARTITION BY p.symbol ORDER BY p.trade_date)
        )
        SELECT g.symbol, g.trade_date, g.prev_date AS last_trade_before, g.n - g.prev_n - 1 AS missed_days
        FROM gaps g
        WHERE g.n - g.prev_n - 1 >= 3
          AND NOT EXISTS (SELECT 1 FROM stock_actions a
                          WHERE a.symbol = g.symbol AND a.split_ratio IS NOT NULL
                            AND a.ex_date > g.prev_date AND a.ex_date <= g.trade_date)
        ORDER BY g.symbol, g.trade_date
        """,
        "多半是分割或減資但 Yahoo 沒有提供事件；查證後補在 scraper/corrections.py",
    ),
    Check(
        "資料庫大小超過 Neon 免費 1 GB 的 80%",
        f"""
        SELECT pg_size_pretty(pg_database_size(current_database())) AS database_size
        WHERE pg_database_size(current_database()) > {int(DATABASE_LIMIT_BYTES * DATABASE_WARN_RATIO)}
        """,
        "超過 1 GB 後無法寫入；用 verify.sql 查詢 20 查看各資料表大小",
    ),
)


@dataclass
class CheckResult:
    check: Check
    rows: list  # 扣除已知例外後的異常資料
    known: int  # 符合已知例外的筆數


def run_checks(conn) -> list[CheckResult]:
    results = []
    for check in CHECKS:
        rows = [r._asdict() for r in conn.execute(text(check.sql))]
        anomalies = [r for r in rows if (r.get("symbol"), r.get("trade_date")) not in check.known]
        results.append(CheckResult(check, anomalies, len(rows) - len(anomalies)))
    return results


def _fmt_row(row: dict) -> str:
    return "、".join(f"{key}={value}" for key, value in row.items())


def log_results(results: list[CheckResult]) -> int:
    """寫入 log，回傳有異常的項目數。"""
    failed = [r for r in results if r.rows]
    # 每項的名稱描述的是「異常的情況」，所以正常時寫「沒有」，例如「✓ 沒有：開高低收不合理」
    for r in results:
        known = f"（已知例外 {r.known} 筆不計）" if r.known else ""
        if not r.rows:
            logger.info("  ✓ 沒有：%s%s", r.check.title, known)
            continue
        logger.warning("  ✗ 發現 %d 筆：%s%s；%s", len(r.rows), r.check.title, known, r.check.hint)
        for row in r.rows[:SAMPLE_ROWS]:
            logger.warning("    %s", _fmt_row(row))
        if len(r.rows) > SAMPLE_ROWS:
            logger.warning("    …其餘 %d 筆（完整清單請執行 sql/verify.sql 對應的查詢）", len(r.rows) - SAMPLE_ROWS)
    if failed:
        logger.warning("資料檢查：%d 項異常（共 %d 項）", len(failed), len(results))
    else:
        logger.info("資料檢查：%d 項全部正常", len(results))
    return len(failed)


def run() -> int:
    try:
        url = build_database_url()
    except ConfigError as exc:
        logger.error("設定錯誤：%s", exc)
        return 2
    # 唯讀連線：檢查本身不會修改任何資料
    engine = create_engine(url, connect_args={"connect_timeout": 15, "options": "-c default_transaction_read_only=on"})
    try:
        with engine.connect() as conn:
            results = run_checks(conn)
            size = conn.execute(text("SELECT pg_database_size(current_database())")).scalar()
        logger.info("資料庫大小 %.0f MB（Neon 免費 1 GB 的 %.0f%%）", size / 1024 ** 2, size / DATABASE_LIMIT_BYTES * 100)
    except SQLAlchemyError as exc:
        logger.error("資料檢查無法執行：%s", getattr(exc, "orig", exc))
        return 3
    finally:
        engine.dispose()
    return 1 if log_results(results) else 0


def main() -> None:
    argparse.ArgumentParser(description="每日資料健康檢查（唯讀）").parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-7s %(message)s", datefmt="%H:%M:%S")
    sys.exit(run())


if __name__ == "__main__":
    main()
