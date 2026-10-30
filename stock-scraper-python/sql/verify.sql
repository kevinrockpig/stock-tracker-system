-- 資料驗證查詢（唯讀）：psql 連線後執行 \i sql/verify.sql，或貼到 Neon Console 的 SQL Editor
-- 涵蓋日 K、技術指標、策略訊號；各查詢的預期結果寫在該查詢的註解中

-- 1. 股票基本資料
SELECT symbol, name, currency, updated_at
FROM stocks
ORDER BY symbol;

-- 2. 每檔股票寫入了幾筆、日期範圍
SELECT symbol,
       COUNT(*)        AS rows,
       MIN(trade_date) AS first_date,
       MAX(trade_date) AS last_date
FROM stock_prices
GROUP BY symbol
ORDER BY symbol;

-- 3. 每檔最新收盤價與前一交易日收盤價（之後 GET /api/stocks 的資料來源）
SELECT symbol, trade_date, close_price, prev_close,
       close_price - prev_close                                   AS change,
       ROUND((close_price - prev_close) / prev_close * 100, 2)    AS change_pct,
       volume
FROM (
    SELECT symbol, trade_date, close_price, volume,
           LAG(close_price) OVER (PARTITION BY symbol ORDER BY trade_date) AS prev_close,
           ROW_NUMBER()     OVER (PARTITION BY symbol ORDER BY trade_date DESC) AS rn
    FROM stock_prices
) t
WHERE rn = 1
ORDER BY symbol;

-- 4. 重複資料檢查：應回傳 0 列
SELECT symbol, trade_date, COUNT(*)
FROM stock_prices
GROUP BY symbol, trade_date
HAVING COUNT(*) > 1;

-- 5. 最近一次寫入 / 更新時間（重複執行後，未變動的資料 updated_at 不會改變）
SELECT symbol, MAX(updated_at) AS last_written
FROM stock_prices
GROUP BY symbol
ORDER BY symbol;

-- 6. 休市日補值資料（成交量 0 且開高低收相同）：應回傳 0 列
SELECT symbol, trade_date, close_price, volume
FROM stock_prices
WHERE volume = 0
  AND open_price = close_price AND high_price = close_price AND low_price = close_price
ORDER BY symbol, trade_date;

-- 7. 開高低收不合理的資料：應回傳 0 列
SELECT symbol, trade_date, open_price, high_price, low_price, close_price
FROM stock_prices
WHERE high_price < GREATEST(open_price, close_price)
   OR low_price  > LEAST(open_price, close_price)
ORDER BY symbol, trade_date;

-- 8. 其他股票都有、這檔卻缺少的交易日（只比對該股票自己的資料範圍）
--    已知例外（2026-10-30 補齊 2015-06-01 起的歷史後確認，共 21 列）：
--      停止交易（正常）：0050.TW 2025-06-11 ~ 06-17（1 拆 4 分割）、2317.TW 2018-10-18 ~ 10-25（減資）
--      yfinance 回傳成交量 0 的補值或缺漏（原因未查證，重新執行也補不回來）：
--        2317.TW 2016-03-30、2454.TW 2016-05-13、2317 / 2330 / 2454 2021-08-17、0050.TW 2024-01-15、
--        2317.TW 2025-07-30、2317 / 2330 / 2454 2025-08-01
--    除上述日期外應回傳 0 列
WITH all_dates AS (
    SELECT DISTINCT trade_date FROM stock_prices
), ranges AS (
    SELECT symbol, MIN(trade_date) AS first_date, MAX(trade_date) AS last_date
    FROM stock_prices
    GROUP BY symbol
)
SELECT r.symbol, d.trade_date AS missing_date
FROM ranges r
JOIN all_dates d ON d.trade_date BETWEEN r.first_date AND r.last_date
LEFT JOIN stock_prices p ON p.symbol = r.symbol AND p.trade_date = d.trade_date
WHERE p.id IS NULL
ORDER BY r.symbol, d.trade_date;

-- 9. 指標與日 K 是否一對一（price_rows 應等於 indicator_rows）
SELECT p.symbol, COUNT(p.id) AS price_rows, COUNT(i.id) AS indicator_rows
FROM stock_prices p
LEFT JOIN stock_indicators i ON i.symbol = p.symbol AND i.trade_date = p.trade_date
GROUP BY p.symbol
ORDER BY p.symbol;

-- 10. 每檔最新一日的技術指標（可與券商軟體對照）
SELECT DISTINCT ON (symbol)
       symbol, trade_date, ma5, ma10, ma20, ma60,
       macd_dif, macd_signal, macd_osc, rsi6, rsi12, kd_k, kd_d
FROM stock_indicators
ORDER BY symbol, trade_date DESC;

-- 11. RSI、K、D 超出 0~100 的資料：應回傳 0 列
SELECT symbol, trade_date, rsi6, rsi12, kd_k, kd_d
FROM stock_indicators
WHERE rsi6 NOT BETWEEN 0 AND 100 OR rsi12 NOT BETWEEN 0 AND 100
   OR kd_k NOT BETWEEN 0 AND 100 OR kd_d NOT BETWEEN 0 AND 100;

-- 12. 最近一年內仍為 NULL 的指標（暖身期應早於最近一年）：應回傳 0 列
SELECT symbol, trade_date
FROM stock_indicators
WHERE trade_date >= CURRENT_DATE - INTERVAL '1 year'
  AND (ma60 IS NULL OR macd_dif IS NULL OR rsi12 IS NULL OR kd_d IS NULL)
ORDER BY symbol, trade_date;

-- 13. 各策略的訊號數量與最近一次出現日期
SELECT strategy, COUNT(*) AS signals, COUNT(DISTINCT symbol) AS symbols, MAX(trade_date) AS last_signal
FROM strategy_signals
GROUP BY strategy
ORDER BY strategy;

-- 14. 市場最新交易日的策略選股結果（與排程 log 最後的「策略選股結果」相同）
SELECT s.strategy, s.symbol, st.name
FROM strategy_signals s
JOIN stocks st ON st.symbol = s.symbol
WHERE s.trade_date = (SELECT MAX(trade_date) FROM stock_prices)
ORDER BY s.strategy, s.symbol;

-- 15. 以 SQL 重新計算策略，與 strategy_signals 比對：兩個方向的差異都應為 0 列
WITH t AS (
    SELECT p.symbol, p.trade_date, p.close_price, i.ma5, i.ma10, i.ma20, i.ma60, i.macd_dif, i.macd_signal,
           LAG(p.close_price)  OVER w AS prev_close,
           LAG(i.ma20)         OVER w AS prev_ma20,
           LAG(i.macd_dif)     OVER w AS prev_dif,
           LAG(i.macd_signal)  OVER w AS prev_signal
    FROM stock_prices p
    JOIN stock_indicators i ON i.symbol = p.symbol AND i.trade_date = p.trade_date
    WINDOW w AS (PARTITION BY p.symbol ORDER BY p.trade_date)
), expected AS (
    SELECT symbol, trade_date, 'ma_bullish' AS strategy FROM t WHERE ma5 > ma10 AND ma10 > ma20 AND ma20 > ma60
    UNION ALL
    SELECT symbol, trade_date, 'golden_cross' FROM t WHERE macd_dif > macd_signal AND prev_dif <= prev_signal
    UNION ALL
    SELECT symbol, trade_date, 'break_ma20' FROM t WHERE close_price > ma20 AND prev_close <= prev_ma20
)
(SELECT 'missing' AS diff, * FROM expected EXCEPT SELECT 'missing', symbol, trade_date, strategy FROM strategy_signals)
UNION ALL
(SELECT 'extra', symbol, trade_date, strategy FROM strategy_signals EXCEPT SELECT 'extra', * FROM expected);

-- 16. 官方開放資料（證交所 / 櫃買中心）累積狀況：每個市場、每個交易日存了幾檔
--     開放資料只提供最近一個交易日；若某個交易日缺漏，代表當天沒有執行，且無法補回
SELECT market, trade_date, COUNT(*) AS securities, COUNT(close_price) AS traded, MIN(source) AS first_source
FROM official_daily_quotes
GROUP BY market, trade_date
ORDER BY trade_date DESC, market;

-- 17. 事件日偵測：一般交易日「漲跌價差 = 收盤 − 前一交易日收盤」，不相等的那天即為除權息、分割或減資等事件
--     （證交所在除權息日的漲跌價差填 0，所以不能用「收盤 − 漲跌」推算參考價）
--     有對應的除權除息預告 → 除權息（附現金股利）；沒有 → 可能是分割、減資，需另行查證
SELECT q.market, q.code, q.name, q.trade_date, q.prev_close, q.close_price, q.change,
       a.action_type, a.cash_dividend, a.stock_dividend_ratio,
       CASE WHEN a.code IS NULL THEN '未在預告表：可能為分割 / 減資' ELSE '除權息' END AS event_guess
FROM (
    SELECT market, code, name, trade_date, close_price, change,
           LAG(close_price) OVER (PARTITION BY market, code ORDER BY trade_date) AS prev_close
    FROM official_daily_quotes
) q
LEFT JOIN official_corporate_actions a
       ON a.market = q.market AND a.code = q.code AND a.ex_date = q.trade_date
WHERE q.prev_close IS NOT NULL AND q.close_price IS NOT NULL AND q.change IS NOT NULL
  AND q.change <> q.close_price - q.prev_close
ORDER BY q.trade_date DESC, q.market, q.code;

-- 18. 官方收盤價與 yfinance 收盤價比對（觀察名單、重疊日期）：收盤價應一致
--     成交量可能不同（官方成交股數涵蓋的交易類型與 yfinance 不同），這裡只比對收盤價
SELECT o.code, o.trade_date, o.close_price AS official_close, p.close_price AS yfinance_close,
       o.volume AS official_volume, p.volume AS yfinance_volume
FROM official_daily_quotes o
JOIN stock_prices p ON p.symbol = o.code || CASE o.market WHEN 'TWSE' THEN '.TW' ELSE '.TWO' END
                   AND p.trade_date = o.trade_date
ORDER BY o.code, o.trade_date;

-- 19. 除權除息預告（最近與即將發生）：現金股利與配股比例，日後計算價格調整時使用
SELECT market, code, name, ex_date, action_type, cash_dividend, stock_dividend_ratio, updated_at
FROM official_corporate_actions
WHERE ex_date >= CURRENT_DATE - INTERVAL '14 days'
ORDER BY ex_date, market, code;

-- 20. 資料庫與各資料表大小（Neon 免費方案每個 project 上限 1 GB，超過後無法寫入）
SELECT pg_size_pretty(pg_database_size(current_database())) AS database_size;
SELECT relname AS table_name, pg_size_pretty(pg_total_relation_size(relid)) AS total_size, n_live_tup AS rows
FROM pg_stat_user_tables
ORDER BY pg_total_relation_size(relid) DESC;

-- 21. 完整歷史涵蓋範圍（回測用）：每檔應從 2015-06-01（config.HISTORY_START）附近開始，history_from 不應為 NULL
SELECT s.symbol, s.name, s.history_from, MIN(p.trade_date) AS first_date, MAX(p.trade_date) AS last_date, COUNT(*) AS days
FROM stocks s
JOIN stock_prices p ON p.symbol = s.symbol
GROUP BY s.symbol, s.name, s.history_from
ORDER BY s.symbol;

-- 22. 除權息與股本事件（yfinance）：現金股利與股數變動（配股、減資、分割），回測計算還原報酬時使用。
--     金額已依之後的配股 / 減資 / 分割換算（與 stock_prices 的價格基準一致），殖利率可用來檢查是否合理
SELECT a.symbol, a.ex_date, a.cash_dividend, a.split_ratio,
       p.close_price AS prev_close,
       ROUND(a.cash_dividend / p.close_price * 100, 2) AS yield_pct
FROM stock_actions a
LEFT JOIN LATERAL (
    SELECT close_price FROM stock_prices
    WHERE symbol = a.symbol AND trade_date < a.ex_date
    ORDER BY trade_date DESC LIMIT 1
) p ON TRUE
ORDER BY a.symbol, a.ex_date;

-- 23. 單日漲跌超過 10%（台股 2015-06-01 起漲跌幅上限為 10%）：超過代表資料錯誤，或除權息 / 股本變動未調整。
--     除權息日的漲跌以「收盤 ÷（前日收盤 − 現金股利）」計算。
--     已知例外：2317.TW 2018-10-26（減資後恢復交易，-10.48%）。推測為減資同時退還股款，
--     Yahoo 只換算了股數比例、未計入退還的現金；回測中跨過這天的交易報酬會略為低估。除此之外應回傳 0 列
SELECT symbol, trade_date, prev_close, cash_dividend, close_price,
       ROUND((close_price / (prev_close - COALESCE(cash_dividend, 0)) - 1) * 100, 2) AS change_pct
FROM (
    SELECT p.symbol, p.trade_date, p.close_price, a.cash_dividend,
           LAG(p.close_price) OVER (PARTITION BY p.symbol ORDER BY p.trade_date) AS prev_close
    FROM stock_prices p
    LEFT JOIN stock_actions a ON a.symbol = p.symbol AND a.ex_date = p.trade_date
) t
WHERE prev_close IS NOT NULL
  AND ABS(close_price / (prev_close - COALESCE(cash_dividend, 0)) - 1) > 0.1005
ORDER BY symbol, trade_date;

-- 24. 回測交易獨立驗算（持有 N 日）：以 SQL 從日 K 與除權息事件重新計算每筆交易，與 backtest_trades 比對；應回傳 0 列
--     進場 = 訊號日的下一個交易日開盤；出場 = 含進場日第 N 個交易日收盤；
--     股利 = 除息日在（進場日, 出場日]；成本 = 手續費 0.1425% × 2 + 證交稅（股票 0.3%、ETF 0.1%）
WITH days AS (
    SELECT symbol, trade_date, open_price, close_price,
           ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY trade_date) AS n
    FROM stock_prices
), calc AS (
    SELECT t.id, t.strategy, t.symbol, t.exit_rule, t.entry_date, t.net_return,
           e.trade_date AS entry_expected, e.open_price AS entry_price,
           x.trade_date AS exit_expected, x.close_price AS exit_price,
           COALESCE((SELECT SUM(cash_dividend) FROM stock_actions a
                     WHERE a.symbol = t.symbol AND a.ex_date > e.trade_date AND a.ex_date <= x.trade_date), 0) AS dividends,
           CASE WHEN t.symbol LIKE '00%' THEN 0.001 ELSE 0.003 END AS tax
    FROM backtest_trades t
    JOIN days s ON s.symbol = t.symbol AND s.trade_date = t.signal_date
    JOIN days e ON e.symbol = t.symbol AND e.n = s.n + 1
    JOIN days x ON x.symbol = t.symbol AND x.n = e.n + CAST(SUBSTRING(t.exit_rule FROM 6) AS INT) - 1
    WHERE t.exit_rule LIKE 'hold_%' AND t.exit_date IS NOT NULL
)
SELECT *, ROUND((exit_price * (1 - 0.001425 - tax) + dividends) / (entry_price * 1.001425) - 1, 6) AS expected_return
FROM calc
WHERE entry_date <> entry_expected
   OR ABS(net_return - ((exit_price * (1 - 0.001425 - tax) + dividends) / (entry_price * 1.001425) - 1)) > 0.000001;

-- 25. 回測不重疊持有：同一（策略, 股票, 出場規則）的每筆交易，進場日都應晚於前一筆的出場日；應回傳 0 列
SELECT strategy, symbol, exit_rule, entry_date, prev_exit
FROM (
    SELECT strategy, symbol, exit_rule, entry_date,
           LAG(exit_date) OVER (PARTITION BY strategy, symbol, exit_rule ORDER BY entry_date) AS prev_exit
    FROM backtest_trades
) t
WHERE prev_exit IS NOT NULL AND entry_date <= prev_exit;

-- 26. 參考價位獨立驗算：以 SQL 重算最近 60 個交易日的波段高低點、黃金分割與 ATR 停損，與 reference_levels 比對；應回傳 0 列
--     （漲跌停價的升降單位已用證交所官方資料逐檔驗證，見 levels.py；這裡只檢查不超過參考價 ±10%）
WITH ranked AS (
    SELECT r.id, p.trade_date, p.high_price, p.low_price,
           ROW_NUMBER() OVER (PARTITION BY r.id ORDER BY p.trade_date DESC) AS k
    FROM reference_levels r
    JOIN stock_prices p ON p.symbol = r.symbol AND p.trade_date <= r.basis_date
), swing AS (
    SELECT id, MAX(high_price) AS hi, MIN(low_price) AS lo,
           MIN(trade_date) FILTER (WHERE high_price = max_hi) AS hi_date,
           MIN(trade_date) FILTER (WHERE low_price = min_lo) AS lo_date
    FROM (SELECT *, MAX(high_price) OVER (PARTITION BY id) AS max_hi, MIN(low_price) OVER (PARTITION BY id) AS min_lo
          FROM ranked WHERE k <= 60) w
    GROUP BY id
)
SELECT r.symbol, r.basis_date, s.hi, s.lo, s.hi_date, s.lo_date, r.swing_high, r.swing_low, r.fib_500, r.atr_stop
FROM reference_levels r
JOIN swing s ON s.id = r.id
JOIN stock_indicators i ON i.symbol = r.symbol AND i.trade_date = r.basis_date
WHERE r.swing_high <> s.hi OR r.swing_low <> s.lo
   OR r.swing_high_date <> s.hi_date OR r.swing_low_date <> s.lo_date
   OR r.swing_trend <> CASE WHEN s.lo_date < s.hi_date THEN 'up' ELSE 'down' END
   OR ABS(r.fib_500 - (s.hi + s.lo) / 2) > 0.0001
   OR ABS(r.fib_382 - CASE WHEN s.lo_date < s.hi_date THEN s.hi - 0.382 * (s.hi - s.lo) ELSE s.lo + 0.382 * (s.hi - s.lo) END) > 0.0001
   OR ABS(r.fib_618 - CASE WHEN s.lo_date < s.hi_date THEN s.hi - 0.618 * (s.hi - s.lo) ELSE s.lo + 0.618 * (s.hi - s.lo) END) > 0.0001
   OR r.atr_stop IS DISTINCT FROM ROUND(r.close_price - 3 * i.atr14, 4)
   OR r.close_price <> (SELECT close_price FROM stock_prices p WHERE p.symbol = r.symbol AND p.trade_date = r.basis_date)
   OR r.limit_up > r.reference_price * 1.1 OR r.limit_down < r.reference_price * 0.9
   OR r.limit_up < r.reference_price * 1.09 OR r.limit_down > r.reference_price * 0.91;
