# stock-api-java

Spring Boot RESTful API：從 PostgreSQL（Neon）讀取 Python 服務寫入的行情、技術指標、策略訊號與策略回測結果，整理成前端需要的 JSON。
本服務**只讀取**資料庫：資料表由 `stock-scraper-python` 建立與寫入，啟動時以 `ddl-auto=validate` 檢查結構是否一致。

## 需求

- JDK 17 以上（IntelliJ 專案 SDK：`homebrew-17`）
- Maven：使用專案內附的 Maven Wrapper（`./mvnw`），不需另外安裝
- 資料庫中已有資料表：請先執行過一次 Python 擷取程式（`python -m scraper.main`）

## 環境變數

| 變數 | 必填 | 說明 |
|---|---|---|
| `DB_URL` | 是 | JDBC 格式，例如 `jdbc:postgresql://<host>:5432/<db>?sslmode=require` |
| `DB_USERNAME` | 是 | 資料庫帳號 |
| `DB_PASSWORD` | 是 | 資料庫密碼 |
| `PORT` | 否 | API port，預設 `8081` |
| `CORS_ALLOWED_ORIGIN` | 否 | 允許跨網域呼叫的前端來源，預設 `http://localhost:5173` |

`application.properties` 只有 `${DB_URL}` 這類佔位符，真實值只放在 IntelliJ 的 Run Configuration 或系統環境變數，不寫進任何檔案。

## 執行

**IntelliJ**：Run → Edit Configurations → `StockApiJavaApplication` → Environment variables 填入上表變數 → Run。
修改或刪除 Java 類別後，先執行 Build → Rebuild Project，避免舊的 `.class` 殘留造成錯誤。

**終端機**：

```bash
cd stock-backend-java/stock-api-java
export DB_URL='jdbc:postgresql://<host>:5432/<db>?sslmode=require' DB_USERNAME='<user>' DB_PASSWORD='<password>'
./mvnw spring-boot:run
```

## API

| 方法與路徑 | 說明 |
|---|---|
| `GET /api/stocks` | 股票清單與最新日收盤報價（含前一交易日收盤、漲跌、漲跌幅） |
| `GET /api/stocks/{symbol}/history?from=&to=` | 歷史日 K 與當日技術指標，`from` / `to` 為 `YYYY-MM-DD`，皆可省略 |
| `GET /api/strategies` | 可用的選股策略（代碼、中文名稱、成立條件） |
| `GET /api/screener?strategy=&days=` | 最近 `days` 個交易日（1～60，預設 1）內符合策略的股票 |
| `GET /api/backtests?scope=` | 策略回測統計（每個策略 × 出場規則一筆）：勝率與信賴區間、平均報酬、相對任意日進場的超額報酬與區間、前後兩段、是否有優勢、買進持有；`scope` 為 `ALL`（預設，全部合併）或股票代號 |
| `GET /api/stocks/{symbol}/trades?strategy=&exitRule=` | 某檔股票的歷史回測交易（進出場日期、價格、股利、報酬），`exitRule` 為 `hold_5`、`hold_10`、`hold_20`、`trailing_atr` |

```bash
curl http://localhost:8081/api/stocks
curl "http://localhost:8081/api/stocks/2330.TW/history?from=2026-10-01"
curl "http://localhost:8081/api/screener?strategy=golden_cross&days=10"
curl "http://localhost:8081/api/backtests"
curl "http://localhost:8081/api/stocks/2330.TW/trades?strategy=golden_cross&exitRule=trailing_atr"
```

- 報價為**日收盤價**（`quoteType: DAILY_CLOSE`），非即時報價
- 日期區間與 `days` 都以交易日計算，週末、國定假日與颱風假不會出現
- 錯誤回應採 RFC 9457 Problem Details 格式，`detail` 欄位有中文說明（例如找不到代號回 404、參數錯誤回 400）

## 程式結構

```
src/main/java/com/ugi/stock/
├── controller/   StockController（/api/stocks）、StrategyController（/api/strategies、/api/screener）、
│                 BacktestController（/api/backtests、/api/stocks/{symbol}/trades）
├── service/      StockService、StrategyService、BacktestService、
│                 Strategy / ExitRule（策略與出場規則代碼，須與 Python 的 STRATEGIES / EXIT_RULES 一致）
├── repository/   Spring Data JPA 查詢
├── entity/       對應資料表，全部標記 @Immutable（唯讀）
└── dto/          API 回應格式（Java record）
```

## 測試

`StockApiJavaApplicationTests.contextLoads` 會啟動完整的 Spring 應用程式，等同檢查「能否連上資料庫，且 Entity 與資料表結構一致」。
因此執行測試時同樣需要上表的 `DB_*` 環境變數，並會連到該資料庫（只讀取，不寫入）：

- **IntelliJ**：測試的執行設定與 `StockApiJavaApplication` 是分開的，需在測試的 Run Configuration 另外填入 `DB_*` 環境變數，否則會出現 `Could not resolve placeholder 'DB_URL'`
- **終端機**：設定環境變數後執行 `./mvnw test`

## 常見錯誤

| 錯誤 | 原因與處理 |
|---|---|
| `Could not resolve placeholder 'DB_URL'` | 未設定環境變數；檢查目前使用的 Run Configuration |
| `Schema-validation: missing table [...]` | 資料庫還沒有該資料表；先執行 Python 擷取程式 |
| `Ambiguous mapping` 或新 API 回 404 | 舊編譯檔殘留；Build → Rebuild Project |
| 前端出現 CORS 錯誤 | 前端來源不是 `http://localhost:5173`；調整 `CORS_ALLOWED_ORIGIN` |
| 第一次請求很慢 | Neon 閒置後會休眠，第一次連線需要喚醒，屬正常現象 |
