# stock-api-java

Spring Boot RESTful API：從 PostgreSQL（Neon）讀取 Python 服務寫入的行情、技術指標、策略訊號與策略回測結果，整理成前端需要的 JSON。
資料表一律由 `stock-scraper-python` 建立，啟動時以 `ddl-auto=validate` 檢查結構是否一致。行情、回測等資料本服務**只讀取**；
唯一的寫入是使用者資料（`app_users`、`user_identities`、`holdings`），依登入者寫入，以及登入狀態（`spring_session`）。
部署時也一併提供前端（Vue build 後的檔案），見根目錄 README「部署」。

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
| `FRONTEND_URL` | 否 | 登入成功 / 失敗後轉回的前端網址，預設 `http://localhost:5173`；部署後設為網站網址或空白（轉回同一個網址） |
| `SESSION_COOKIE_SECURE` | 否 | 正式環境（HTTPS）設為 `true`，登入 cookie 只經由 HTTPS 傳送；預設 `false` |
| `RATE_LIMIT_PER_MINUTE` | 否 | 每個 IP 每分鐘最多請求數（含網頁檔案），超過回 429；預設 `200`，`0` 表示不限制 |
| `MAIL_USERNAME` | 否（每日通知時必填） | 寄出每日通知的 Gmail 帳號；未設定時通知模式不寄信 |
| `MAIL_PASSWORD` | 否（每日通知時必填） | 該 Gmail 帳號的「應用程式密碼」（需先開啟兩步驟驗證），不是登入密碼 |
| `SITE_URL` | 否 | 網站網址，信中附上通知設定的連結；尚未部署時空白 |
| `MAIL_HOST`、`MAIL_PORT`、`MAIL_FROM_NAME` | 否 | 預設 `smtp.gmail.com`、`587`、「股票追蹤系統」 |

`application.properties` 只有 `${DB_URL}` 這類佔位符，真實值只放在 IntelliJ 的 Run Configuration 或系統環境變數，不寫進任何檔案。

## 登入（Google）

- 前端導向 `/oauth2/authorization/google` → Google 驗證 → 回到 `/login/oauth2/code/google`（開發時經 Vite 轉發，
  實際網址為 `http://localhost:5173/login/oauth2/code/google`，需在 Google Cloud 的 OAuth 用戶端登記）
- 只要求 `openid`、`email`、`profile` 三項基本資料；email 必須已由 Google 驗證且在 `AUTH_ALLOWED_EMAILS` 內，
  否則拒絕登入、不寫入任何資料，並轉回 `/holdings?login=not_allowed`
- 登入狀態存在資料庫（Spring Session JDBC，`spring_session` / `spring_session_attributes`，由 Python 建立；cookie 名稱 `SESSION`，
  HttpOnly、SameSite=Lax），閒置 12 小時後需重新登入；Java 重新啟動或部署後仍保持登入。
  過期的 session 每天 15:30 清除一次（預設每分鐘一次，會讓 Neon 無法休眠）。
  升級 Spring Security 的版本後，舊的 session 可能無法讀回，屆時清空 `spring_session` 即可（使用者重新登入）
- 寫入請求（POST / PUT / DELETE，含 `POST /api/logout`）需附 CSRF token：`GET /api/me` 會寫入 `XSRF-TOKEN` cookie，
  前端把它的值放進 `X-XSRF-TOKEN` 標頭
- 既有 API 維持公開；`/api/holdings/**`、`/api/my-stocks/**`、`/api/notifications/**` 需要登入，未登入回 401

## 庫存（holdings）

- 一律以登入者過濾：查不到（包含別人的）回 404；每人最多 200 筆；股票必須在觀察名單內或自己的自選中（且已有資料），買進日期不可晚於今天、不可早於上市日
- 手續費：`standard` = 成交金額 × 0.1425%、`discount` 再乘折數，兩者無條件捨去到元並套用最低手續費（未填為 20 元）；
  `actual` 照填。各券商規則不同，畫面標示為估算
- 換算基準：價格與股利都是 Yahoo 依分割 / 配股 / 減資調整後的基準。有填買進日期時，依 `stock_actions` 中買進日之後的
  股數變動倍數換算股數，股利 = 每股股利 × 換算後股數；沒有買進日期時視為已是目前基準，不計股利
  （Yahoo 漏掉的事件由 Python 的 `scraper/corrections.py` 補上，例如 0050 於 2025-06-18 的 1 拆 4）
- 賣出成本：手續費依該檔最近一筆的方式（`actual` 以公定費率、最低 20 元估算）＋證交稅（股票 0.3%、ETF 0.1%）
- 移動停利線：最早買進日以來的最高收盤價 − 3 × 最新 ATR(14)，與模擬帳戶相同
- 同期比較：每筆有日期的買進，假設同一天（非交易日為下一個交易日）以同樣成本在收盤買 0050，含股利、不計交易成本

## 每日通知（Email）

每日更新後由 GitHub Actions 以「通知模式」執行一次（`java -jar … --app.notify.run-once=true --server.port=0`），
寄完就結束；網站本身不寄信。設定檔見 `stock-scraper-python/deploy/data-archive-repo/.github/workflows/market-daily.yml`。

- 收件人：在我的庫存頁開啟 Email 通知的使用者，寄到登入的 Google email；每個交易日一封，沒有提醒時也寄（「今日無提醒」）
- 內容（`NotificationService`，數字與我的庫存、交易計畫相同）：大盤（0050 收盤與季線）、跌破移動停利線、
  接近移動停利線（距離停利線幾個 ATR 以內，依個股風險設定：保守 1.5、標準 1、積極 0.5；0050 收盤低於季線時各加 0.5）、
  幾天內除權息（預設 3 天）、交易計畫下一個交易日的買進候選（只寫「符合條件」，不是建議）
- 每個交易日只寄一次（`notification_log`）：備援重跑或休市日執行時，依據的交易日沒有變，不會重寄；寄送失敗的下次執行重試
- `--app.notify.dry-run=true`：只把內容寫進 log，不寄出、不記錄（檢查內容用）
- Gmail 個人帳號每天最多寄 500 封，超過時暫停 1～24 小時（Google 說明）；每人每天一封遠低於上限

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
| `GET /api/today` | 今天頁（不需登入）：`asOf`（最新交易日）、`market`（0050 收盤、60 日均線、`canOpen` 是否在均線之上、相差 %）、`candidates`（R1 規則的候選：最新交易日出現「創 120 日新高」或「季線上揚時 MACD 黃金交叉」的觀察名單股票，不含 0050；登入時另含自己的自選，`mine` 為 true；每檔有訊號、近 120 日漲幅 `strength`、停損參考 `stopPrice`＝收盤 × 0.9、`skipAbove`＝收盤 × 1.095；依 `strength` 由高到低）。規則常數在 `PlanRules`，與研究程式一致 |
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
| `GET /api/holdings` | **需登入**。自己的庫存與分析：依股票合併的成本、市值、未實現損益（扣賣出手續費與證交稅估算）、已領股利、移動停利線與距離、季線、除權息預告、目前訊號；合計；同期買 0050 比較；已實現損益（`realized`：每筆賣出、已全部賣出的股票、總損益）。賣出依先進先出（與券商相同，沒有買進日期的視為最早）即時分配，不另存；持股的股數與成本只計還沒賣出的部分 |
| `GET /api/holdings/history` | **需登入**。資產走勢：每個交易日的投入成本、庫存價值（市值 − 賣出成本 + 已領股利）、同期買 0050 的價值；納入的庫存與算法同「同期比較」，最後一天等於其數字。不存快照，每次由買進紀錄、日 K、除權息即時計算 |
| `POST /api/holdings` | **需登入、CSRF**。新增一筆買進：`symbol`、`buyDate`（選填）、`price`、`shares`、`feeMode`（`actual` / `standard` / `discount`）、`feeDiscount`、`minFee`、`fee`；回 201 與該筆內容（含計算出的手續費） |
| `PUT /api/holdings/{id}` / `DELETE /api/holdings/{id}` | **需登入、CSRF**。修改 / 刪除自己的一筆；別人的或不存在回 404；修改或刪除後會讓之前的賣出超過當時持有的股數時回 400 |
| `POST /api/holdings/sales` | **需登入、CSRF**。新增一筆賣出：`symbol`、`sellDate`（必填）、`price`、`shares`（賣出當時的股數）、`feeMode` / `feeDiscount` / `minFee` / `fee`（同買進）、`taxMode`（`standard` 依稅率 / `actual`）、`tax`；回 201 與該筆（含先進先出的分配與已實現損益）。超過當時可賣的股數回 400 |
| `PUT /api/holdings/sales/{id}` / `DELETE /api/holdings/sales/{id}` | **需登入、CSRF**。修改 / 刪除自己的一筆賣出 |
| `GET /api/my-stocks` | **需登入**。我的自選（自行加入、不限觀察名單的股票），依加入時間排序：`symbol`、`name`、`addedAt`、`ready`（是否已有資料；剛加入的要等下一次每日更新）、`watchlist`（是否也在觀察名單）、`quote`（格式同 `GET /api/stocks`，沒有資料時為 null）；`limit` 為每人上限 |
| `POST /api/my-stocks` | **需登入、CSRF**。加入一檔：`code`（不含 .TW / .TWO，例如 `6488`、`00878`）；以官方每日資料（最近 7 天）確認是目前上市、上櫃的股票或 ETF，並決定代號為 `.TW` 或 `.TWO`。每人最多 30 檔；全站自行加入、不在觀察名單的股票最多 200 檔（與 Python 的 `MAX_REQUESTED_STOCKS` 相同，已有人加入或在觀察名單的不受限）；格式錯誤、查不到、重複或超過上限回 400。下一次每日更新後就有 K 線、指標、參考價位，也可以加入庫存 |
| `DELETE /api/my-stocks/{symbol}` | **需登入、CSRF**。移除自己的一檔（例如 `6488.TWO`）；不存在回 404。沒有人加入也沒有人持有的股票，每日更新就不再抓（資料保留） |
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
│                 PortfolioController（/api/portfolio-backtests）、PaperController（/api/paper）、AuthController（/api/me）、HoldingController（/api/holdings）、MyStockController（/api/my-stocks）、TodayController（/api/today）
├── service/      StockService、StrategyService、BacktestService、PortfolioService、PaperService、ReferenceLevelService、AppUserService、HoldingService、TradingCosts（交易成本）、
│                 Strategy / ExitRule / Horizon / StockGroup
│                 （策略、出場規則、短線 / 波段、組別代碼，須與 Python 的 STRATEGIES / EXIT_RULES / STOCK_GROUPS 一致）
├── repository/   Spring Data JPA 查詢
├── security/     SecurityConfig（存取控制、CSRF、Google 登入）、AuthSettings（允許名單）、AllowedUserOidcService、AppUserPrincipal
├── entity/       對應資料表；行情與回測標記 @Immutable（唯讀），AppUser / UserIdentity / Holding 可寫入
└── dto/          API 回應格式（Java record）
```

## 測試

`StockApiJavaApplicationTests.contextLoads` 會啟動完整的 Spring 應用程式，等同檢查「能否連上資料庫，且 Entity 與資料表結構一致」。
因此執行測試時同樣需要上表的 `DB_*` 環境變數，並會連到該資料庫。會寫入的測試（使用者、庫存：`AppUserServiceDatabaseTests`、`HoldingServiceTests`、`HoldingApiTests`）
都在交易中執行並於結束時還原（rollback），不會留下資料。測試預設不使用資料庫的 session（`src/test/resources/config/application.properties`），
只有 `SessionPersistenceTests` 會寫入一筆並在結束時刪除。`HoldingServiceTests` 以 SQL 直接重算分析數字並逐項比對。登入相關測試使用測試用的假 Google 用戶端，不連線 Google：

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
| 登入時出現 `relation "spring_session" does not exist` | Python 還沒建立登入狀態資料表；執行一次 `python -m scraper.main`（或等每日排程） |
| 回應 429「請求太頻繁」 | 同一個 IP 每分鐘超過 `RATE_LIMIT_PER_MINUTE`；開發時大量測試可設為 `0` |
