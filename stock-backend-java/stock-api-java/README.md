# stock-api-java

Spring Boot RESTful API：從 PostgreSQL（Neon）讀取 Python 服務寫入的行情、技術指標、策略訊號與策略回測結果，整理成前端需要的 JSON。
資料表一律由 `stock-scraper-python` 建立，啟動時以 `ddl-auto=validate` 檢查結構是否一致。行情、回測等資料本服務**只讀取**；
唯一的寫入是使用者資料（`app_users`、`user_identities`，以及之後的庫存），依登入者寫入。

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
| `GOOGLE_CLIENT_ID` | 否 | Google OAuth 用戶端 ID；未設定時登入功能關閉，其餘 API 照常 |
| `GOOGLE_CLIENT_SECRET` | 否（登入時必填） | Google OAuth 用戶端密鑰 |
| `AUTH_ALLOWED_EMAILS` | 否（登入時必填） | 允許登入的 Google 帳號 email，以逗號分隔；空白時任何人都無法登入 |
| `FRONTEND_URL` | 否 | 登入成功 / 失敗後轉回的前端網址，預設 `http://localhost:5173` |
| `SESSION_COOKIE_SECURE` | 否 | 正式環境（HTTPS）設為 `true`，登入 cookie 只經由 HTTPS 傳送；預設 `false` |

`application.properties` 只有 `${DB_URL}` 這類佔位符，真實值只放在 IntelliJ 的 Run Configuration 或系統環境變數，不寫進任何檔案。

## 登入（Google）

- 前端導向 `/oauth2/authorization/google` → Google 驗證 → 回到 `/login/oauth2/code/google`（開發時經 Vite 轉發，
  實際網址為 `http://localhost:5173/login/oauth2/code/google`，需在 Google Cloud 的 OAuth 用戶端登記）
- 只要求 `openid`、`email`、`profile` 三項基本資料；email 必須已由 Google 驗證且在 `AUTH_ALLOWED_EMAILS` 內，
  否則拒絕登入、不寫入任何資料，並轉回 `/holdings?login=not_allowed`
- 登入狀態存在伺服器端 session（cookie 為 HttpOnly、SameSite=Lax），閒置 12 小時或 Java 重新啟動後需重新登入
- 寫入請求（POST / PUT / DELETE，含 `POST /api/logout`）需附 CSRF token：`GET /api/me` 會寫入 `XSRF-TOKEN` cookie，
  前端把它的值放進 `X-XSRF-TOKEN` 標頭
- 既有 API 維持公開；`/api/holdings/**` 需要登入，未登入回 401

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
| `GET /api/stocks` | 觀察名單與最新日收盤報價（含前一交易日收盤、漲跌、漲跌幅、所屬組別、上市日期） |
| `GET /api/groups` | 觀察名單的組別（`TW50` 0050 成分股、`CUSTOM` 自選）與股票數 |
| `GET /api/stocks/{symbol}/history?from=&to=` | 歷史日 K 與當日技術指標（MA、MACD、RSI、KD、ATR14），`from` / `to` 為 `YYYY-MM-DD`，皆可省略 |
| `GET /api/stocks/{symbol}/levels` | 最新的參考價位（供下一個交易日參考）：ATR 停損、60 日波段與黃金分割、參考價與漲跌停價、除權息預告；尚未計算時回 404 |
| `GET /api/strategies` | 可用的選股策略（代碼、中文名稱、成立條件） |
| `GET /api/screener?strategy=&days=` | 最近 `days` 個交易日（1～60，預設 1）內符合策略的股票 |
| `GET /api/backtests?scope=` | 策略回測統計（每個策略 × 出場規則一筆）：勝率與信賴區間、平均報酬、相對任意日進場的超額報酬與區間（依進場月份分群）、前後兩段、是否有優勢、資金曲線（年化、最大回撤、在場時間）與買進持有；出場規則分短線 / 波段兩組（`horizons` 含各組比較組數）；`scope` 為 `ALL`（預設）、組別代碼或股票代號 |
| `GET /api/portfolio-backtests?scope=` | 投資組合回測（一筆共用資金、最多同時持有 10 檔）：年化、最大回撤、Calmar（整段與前後兩段）、Sharpe、平均持股比例、各亂數種子的年化範圍；基準為同一批股票等額買進持有，另附 0050 ETF 參考。每組附標準 1（`signalEdge`，個別回測的訊號優勢）、標準 2（`beatsBenchmark`，Calmar 整段與前後段都勝過基準）與 `effective`（兩者皆通過）；`scope` 為 `ALL`（預設）或組別代碼，不提供單一股票 |
| `GET /api/paper` | 交易計畫與往後實測：每個觀察中策略的模擬帳戶，包含待執行的計畫（買進候選與放棄價、ATR 停損，賣出與停利線）、當天執行結果、持有中部位（目前價、報酬、市值占比、目前出場線）、已出場交易、每日資金與同期買進持有 / 0050 基準 |
| `GET /api/me` | 目前的登入狀態（`loginEnabled`、`authenticated`、`email`、`displayName`、`loginUrl`），並寫入 CSRF token cookie |
| `POST /api/logout` | 登出（需 CSRF token），回 204 |
| `GET /api/stocks/{symbol}/trades?strategy=&exitRule=` | 某檔股票的歷史回測交易（進出場日期、價格、股利、報酬）；`exitRule` 短線為 `hold_5`、`hold_10`、`hold_20`、`trailing_atr`，波段為 `ma60_exit`、`trailing_nocap`；波段策略只搭配波段出場（其他組合回 400） |

```bash
curl http://localhost:8081/api/stocks
curl "http://localhost:8081/api/stocks/2330.TW/history?from=2026-10-01"
curl http://localhost:8081/api/stocks/2330.TW/levels
curl "http://localhost:8081/api/screener?strategy=golden_cross&days=10"
curl "http://localhost:8081/api/backtests"
curl "http://localhost:8081/api/backtests?scope=TW50"
curl http://localhost:8081/api/groups
curl "http://localhost:8081/api/portfolio-backtests?scope=TW50"
curl http://localhost:8081/api/paper
curl "http://localhost:8081/api/stocks/2330.TW/trades?strategy=golden_cross&exitRule=trailing_atr"
```

- 報價為**日收盤價**（`quoteType: DAILY_CLOSE`），非即時報價
- 日期區間與 `days` 都以交易日計算，週末、國定假日與颱風假不會出現
- 錯誤回應採 RFC 9457 Problem Details 格式，`detail` 欄位有中文說明（例如找不到代號回 404、參數錯誤回 400）

## 程式結構

```
src/main/java/com/ugi/stock/
├── controller/   StockController（/api/stocks）、StrategyController（/api/strategies、/api/screener）、
│                 BacktestController（/api/backtests、/api/stocks/{symbol}/trades）、
│                 ReferenceLevelController（/api/stocks/{symbol}/levels）、StockGroupController（/api/groups）、
│                 PortfolioController（/api/portfolio-backtests）、PaperController（/api/paper）、AuthController（/api/me）
├── service/      StockService、StrategyService、BacktestService、PortfolioService、PaperService、ReferenceLevelService、AppUserService、
│                 Strategy / ExitRule / Horizon / StockGroup
│                 （策略、出場規則、短線 / 波段、組別代碼，須與 Python 的 STRATEGIES / EXIT_RULES / STOCK_GROUPS 一致）
├── repository/   Spring Data JPA 查詢
├── security/     SecurityConfig（存取控制、CSRF、Google 登入）、AuthSettings（允許名單）、AllowedUserOidcService、AppUserPrincipal
├── entity/       對應資料表；行情與回測標記 @Immutable（唯讀），AppUser / UserIdentity 可寫入
└── dto/          API 回應格式（Java record）
```

## 測試

`StockApiJavaApplicationTests.contextLoads` 會啟動完整的 Spring 應用程式，等同檢查「能否連上資料庫，且 Entity 與資料表結構一致」。
因此執行測試時同樣需要上表的 `DB_*` 環境變數，並會連到該資料庫。測試只讀取；唯一寫入的 `AppUserServiceDatabaseTests`
在交易中執行並於結束時還原（rollback），不會留下資料。登入相關測試使用測試用的假 Google 用戶端，不連線 Google：

- **IntelliJ**：測試的執行設定與 `StockApiJavaApplication` 是分開的，需在測試的 Run Configuration 另外填入 `DB_*` 環境變數，否則會出現 `Could not resolve placeholder 'DB_URL'`
- **終端機**：設定環境變數後執行 `./mvnw test`

## 常見錯誤

| 錯誤 | 原因與處理 |
|---|---|
| `Could not resolve placeholder 'DB_URL'` | 未設定環境變數；檢查目前使用的 Run Configuration |
| `Schema-validation: missing table [...]` | 資料庫還沒有該資料表；先執行 Python 擷取程式 |
| `Ambiguous mapping` 或新 API 回 404 | 舊編譯檔殘留；Build → Rebuild Project |
| `Schema-validation: missing table [app_users]` | Python 還沒建立使用者資料表；執行一次 `python -m scraper.main`（或等每日排程） |
| 登入後出現 `redirect_uri_mismatch` | Google Cloud 的 OAuth 用戶端沒有登記 `http://localhost:5173/login/oauth2/code/google`（需完全相同） |
| 登入後轉回 `?login=not_allowed` | 該 Google 帳號不在 `AUTH_ALLOWED_EMAILS` |
| 前端出現 CORS 錯誤 | 前端來源不是 `http://localhost:5173`；調整 `CORS_ALLOWED_ORIGIN` |
| 第一次請求很慢 | Neon 閒置後會休眠，第一次連線需要喚醒，屬正常現象 |
