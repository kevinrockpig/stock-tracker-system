# stock-scraper-python

用 yfinance 擷取觀察名單的日行情，經 Pandas 清理後寫入 PostgreSQL（Neon），並計算技術指標、掃描選股策略。
資料表 `stocks`、`stock_prices`、`stock_actions`、`stock_indicators`、`strategy_signals` 由本專案建立與維護，Java API 只讀取。

## 安裝（建議 Python 3.12，最低 3.11）

```bash
cd stock-scraper-python
python3.12 -m venv venv
source venv/bin/activate          # Windows：venv\Scripts\activate
pip install -r requirements.txt
```

## 設定資料庫連線

```bash
cp .env.example .env              # 再填入實際的 DB_URL / DB_USERNAME / DB_PASSWORD
```

- `DB_URL` 可直接使用與 Java 相同的 JDBC 格式：`jdbc:postgresql://<host>:5432/<db>?sslmode=require`
- 帳密不要寫在 URL 中；`.env` 已被 `.gitignore` 排除
- 系統環境變數優先於 `.env`

## 執行

```bash
python -m scraper.main                 # 增量抓日 K（OHLCV），並計算技術指標、掃描策略
python -m scraper.main --full          # 強制重抓 2015-06-01 起的完整歷史（平常不需要）
```

**增量抓取**：資料存在自己的資料庫，每次只從資料庫最新的日期接著抓，並往前重疊 10 個交易日（`config.OVERLAP_TRADING_DAYS`），
用來接收 Yahoo 對最近幾天的修正，以及偵測下面的價格重新調整。電腦關機多久，下次執行就從那裡接著補。

**完整歷史（回測用）**：資料庫保存 2015-06-01（`config.HISTORY_START`，台股漲跌幅自這天起放寬為 10%）起的日 K。
以下情況會自動改抓完整歷史，不需要手動處理：

- 資料庫還沒有這檔股票的完整歷史（例如第一次執行，或新加入觀察名單）
- Yahoo 因配股、減資、分割重新調整了歷史價格：比對重疊日期的收盤價，整體差超過 0.1% 就重抓
  （Yahoo 不一定會記錄分割事件，例如 0050 的 1 拆 4，所以用價格比對而不是看事件紀錄）

除權息與股本事件（現金股利、配股 / 減資 / 分割比例）存在 `stock_actions`，回測時用來計算含股利的還原報酬。
注意：最近一次配股或減資**之前**的價格是 Yahoo 換算後的數值，與當年的實際報價不同（例如鴻海 2018 年減資前的價格 ÷ 0.8），
報酬率不受影響。

清理規則（不寫入資料庫，並在 log 中列出日期）：

- 收盤價為空的資料
- 休市日補值：成交量 0 且開高低收相同（資料庫中已存在的也會刪除）
- 開高低收不合理：最高價低於開盤 / 收盤，或最低價高於開盤 / 收盤

價格未還原除息（與券商報價一致），除息日的 K 線會出現跳空缺口；股票分割則由 Yahoo 調整過，分割前後的價格可連續比較（例如 0050 於 2025 年 6 月分割）。

| exit code | 意義 |
|---|---|
| 0 | 全部成功 |
| 1 | 部分股票擷取或寫入失敗（其餘仍會寫入） |
| 2 | 環境變數缺漏或格式錯誤 |
| 3 | 資料庫連線失敗，或既有資料表結構不符 |

可重複執行：同一股票、同一交易日只會有一筆；數值沒變就不更新。

## 驗證

在 Neon Console 的 SQL Editor 貼上 `sql/verify.sql` 執行，或使用 psql：

```bash
psql "host=<host> dbname=<db> user=<user> sslmode=require" -f sql/verify.sql
```

查詢 4、6、7、11、12 應回傳 0 列；查詢 8（缺漏交易日）有已知例外，列在查詢的註解中；查詢 9 的兩個筆數應相同；查詢 16～20 為官方開放資料的累積狀況、事件日偵測、收盤價比對、除權除息預告與資料庫用量；查詢 21～23 為完整歷史的涵蓋範圍、除權息事件與單日漲跌異常（23 有一個已知例外）；查詢 24、25 以 SQL 獨立驗算回測交易，應回傳 0 列。

## 技術指標

每次執行在寫入日 K 後，會從資料庫讀回該股票全部日 K，計算指標並寫入 `stock_indicators`（與 `stock_prices` 一對一，外鍵 `ON DELETE CASCADE`）。

| 欄位 | 指標 | 計算方式 |
|---|---|---|
| `ma5` `ma10` `ma20` `ma60` | 移動平均 | TA-Lib `SMA`，以交易日計算 |
| `macd_dif` `macd_signal` `macd_osc` | MACD（12, 26, 9） | TA-Lib `MACD`；台股名稱 DIF / MACD / OSC |
| `rsi6` `rsi12` | RSI | TA-Lib `RSI`（Wilder 平滑） |
| `kd_k` `kd_d` | KD（9, 3, 3） | 自行實作台股公式（1/3 平滑、起始值 50），TA-Lib 的 STOCH 與台股不同 |

MACD、RSI、KD 前 120 筆（2015 年下半年）為暖身期，存為 NULL。指標每次都以資料庫中的全部日 K 計算，與每次從 Yahoo 抓多少無關。

## 策略掃描

計算指標後，依每個交易日的收盤價與指標判斷策略，結果寫入 `strategy_signals`（只存符合的組合，每次執行與計算結果同步：新增缺少的、刪除不再成立的）。

| 代碼 | 名稱 | 成立條件（「前一日」為前一個交易日） |
|---|---|---|
| `ma_bullish` | 均線多頭排列 | MA5 > MA10 > MA20 > MA60 |
| `golden_cross` | MACD 黃金交叉 | 前一日 DIF ≤ MACD，當日 DIF > MACD（只在交叉當天成立） |
| `break_ma20` | 突破 20 日均線 | 前一日收盤 ≤ MA20，當日收盤 > MA20（只在突破當天成立） |

執行結束時 log 會列出市場最新交易日各策略符合的股票。

## 策略回測

```bash
python -m scraper.backtest     # 依資料庫中的歷史訊號重新計算（每日 scraper.main 結束時也會自動執行）
```

依 `strategy_signals` 的歷史訊號模擬進出場，結果寫入 `backtest_trades`（每筆交易）與 `backtest_stats`（統計），
規則固定、不做參數最佳化：

| 項目 | 規則 |
|---|---|
| 進場 | 訊號日的下一個交易日開盤價；開盤已接近漲停（+9.5% 以上，除息日以扣除股利後的參考價計算）視為買不到 |
| 出場 | 4 種各自統計：持有 5 / 10 / 20 個交易日（含進場日）於收盤賣出；移動停利（進場後最高收盤 − 3 × ATR(14)，收盤跌破則隔日開盤賣出，最長 60 日） |
| 重複訊號 | 同一股票、策略、出場規則同時只持有一筆；均線多頭排列只在進入多頭的第一天算訊號 |
| 成本 | 買賣手續費各 0.1425%（不打折）、賣出證交稅股票 0.3% / ETF 0.1% |
| 股利 | 除息日在（進場日, 出場日]的現金股利計入報酬 |
| 期間 | 指標暖身完成後（約 2015-11）到最新一天 |

統計包含交易次數、勝率與 95% 信賴區間（Wilson）、平均 / 中位數報酬、盈虧比、獲利因子、最大虧損、最大連續虧損次數、平均持有天數，
並與兩個基準比較：**任意一個交易日都依相同規則進場**的平均結果（判斷訊號有沒有加分），以及同期間**買進持有**的報酬。

**有沒有優勢**：超額報酬 = 每筆交易報酬 − **同一檔股票**任意日進場的平均報酬，以 t 分布計算 95% 信賴區間，
**下限大於 0** 時 `has_edge` 為 true。這只是最低門檻：不同股票常同時漲跌，合併統計的區間可能偏窄；
而且同時比較 12 組（3 策略 × 4 出場規則）時，純粹靠運氣也可能有 1 組通過。

已知限制：假設一定能以開盤 / 收盤價成交（未考慮跌停賣不掉、滑價）；觀察名單是事後挑選的大型股；yfinance 少數交易日缺漏（查詢 8）；
鴻海 2018 年減資退還的股款未計入（查詢 23）。

## 官方開放資料每日累積

```bash
python -m scraper.official
```

每天取得證交所（上市）與櫃買中心（上櫃）全部股票與 ETF 的資料，原樣存入資料庫（與 yfinance 的 `stock_prices` 分開，互不影響），並另存 CSV 備份。

| 來源 | 網址 | 寫入 |
|---|---|---|
| 證交所 日成交 CSV | `twse.com.tw/exchangeReport/STOCK_DAY_ALL?response=open_data` | `official_daily_quotes`（收盤後當日更新） |
| 證交所 日成交 OpenAPI | `openapi.twse.com.tw/v1/exchangeReport/STOCK_DAY_ALL` | 同上（可能比 CSV 晚一天，兩者都抓可互相補漏） |
| 櫃買中心 日成交 OpenAPI | `tpex.org.tw/openapi/v1/tpex_mainboard_daily_close_quotes` | 同上（另提供官方次一交易日參考價） |
| 證交所 除權除息預告 | `openapi.twse.com.tw/v1/exchangeReport/TWT48U_ALL` | `official_corporate_actions` |
| 櫃買中心 除權除息預告 | `tpex.org.tw/openapi/v1/tpex_exright_prepost` | 同上 |

- **授權**：政府資料開放授權條款－第 1 版（https://data.gov.tw/license），可商業使用與散布；對外顯示時須依條款格式標示來源
- **只提供當下的資料**：每天都要執行，漏掉的日子無法補回；休市日執行會取得前一交易日資料（未變動）
- 只保留股票與 ETF（代號 4 碼，或 `00` 開頭），排除權證與特別股
- 下載時連線中斷、逾時或伺服器暫時錯誤會自動重試（最多 3 次，間隔 5、10 秒）；單一來源失敗不影響其他來源
- 不做除權息 / 分割調整，原始數值完整保存，日後可重新計算
- **證交所在除權息日的「漲跌價差」填 0**，不能用「收盤 − 漲跌」推算當日參考價。事件日改以「漲跌價差 ≠ 收盤 − 前日收盤」偵測，事件類型與現金股利由除權除息預告表判斷（`sql/verify.sql` 查詢 17）
- 官方成交量與 yfinance 不同（涵蓋的交易類型不同），收盤價則一致（查詢 18）

**CSV 備份**：每個來源每天一個 `<來源>/<年>/<日期>.csv.gz`，保留官方原始欄位，可直接用 Excel 開啟或重新匯入。
存放位置為環境變數 `ARCHIVE_DIR`，未設定時為 `archive/`（不會 commit）。內容相同時不改寫檔案。

**空間監控**：每次執行最後顯示資料庫用量；超過上限（預設 1024 MB，可用 `DB_STORAGE_LIMIT_MB` 調整）的 80% 時發出警告。

### 雲端每日排程（GitHub Actions）

官方資料漏抓就無法補回，因此另外在 GitHub Actions 每天自動執行 `scraper.official`，不依賴這台電腦開機。
設定檔與說明在 [`deploy/data-archive-repo/`](deploy/data-archive-repo/README.md)，要複製到另一個**私人 repo** 使用：

- 公開 repo 的 Actions 執行紀錄任何人都看得到，紀錄中會出現資料庫主機等資訊；CSV 備份也不宜公開散布
- 私人 repo 取出這個公開 repo 的程式碼執行，資料庫連線資訊放在私人 repo 的 Secrets，CSV 備份 commit 回私人 repo
- 週二到週六台灣時間 08:17、11:47（備援）各執行一次，抓前一個交易日：
  實測櫃買中心約晚上 11 點半、證交所 OpenAPI 約隔天清晨 5 點才更新，證交所 CSV 則在隔天下午 2 點左右換成下一天
- 失敗時 GitHub 會寄信通知，可在 Actions 頁面按「Run workflow」重跑，或在本機點一鍵更新補抓
- 只有 `scraper.official` 在雲端執行；觀察名單（`scraper.main`）仍以手動一鍵更新為主

## 手動一鍵更新

在 Finder 點兩下 `scripts/update-stock-data.command`：開啟終端機視窗顯示進度，結束後顯示成功或失敗，按任意鍵關閉。
等同於在終端機執行 `./scripts/run_daily.sh`，依序執行 `scraper.main`（觀察名單）與 `scraper.official`（官方資料累積），兩者互相獨立。輸出同時附加到 `logs/daily-YYYY-MM.log`。

建立桌面捷徑（只需做一次）：

```bash
ln -s "$(pwd)/scripts/update-stock-data.command" ~/Desktop/更新股票資料.command
```

- 只能在這台電腦、你的帳號下執行：需要 `.env` 的資料庫連線資訊；網站（Vue / Java）沒有任何觸發入口
- `.env` 應設為只有自己能讀（`chmod 600 .env`），權限過寬時程式會提醒
- 同時只會有一個更新在執行（連點兩次，或與自動排程同時觸發時，後來的會直接結束）
- 休市日（國定假日、颱風假）執行不會出錯，結果維持前一個交易日
- 只會對最近 20 個交易日內的缺漏發出警告；更早的已知 yfinance 缺漏列在 `sql/verify.sql` 查詢 8

## 每日自動排程（macOS launchd）

```bash
./scripts/install_schedule.sh             # 安裝（或更新設定）：週一至週五 15:00 自動執行
./scripts/install_schedule.sh status      # 是否已安裝、上次執行結果、最近的紀錄
./scripts/install_schedule.sh run         # 立即在背景執行一次（測試用）
./scripts/install_schedule.sh uninstall   # 移除排程
```

- 執行內容與一鍵更新相同（`run_daily.sh`），結束後以 macOS 通知顯示結果；一鍵更新仍可隨時使用，兩者不會同時執行
- 排定時間若電腦在**睡眠**中，喚醒後補執行一次，並先等網路連線（最多 60 秒）
- **關機或登出**時不會執行，開機後也不補執行：觀察名單每次從資料庫最新的日期接著抓，下次執行就會補齊；
  官方開放資料則由 GitHub Actions 每天累積（見上方「雲端每日排程」），不受影響
- 只在目前使用者的帳號下執行，不需要 sudo；設定檔在 `~/Library/LaunchAgents/com.stocktracker.daily-scraper.plist`
- 執行中途關機或被強制結束時留下的執行鎖，下次執行會自動清除

## 單元測試

```bash
pip install -r requirements-dev.txt
python -m pytest
```

測試只使用程式內產生的小型資料，**不連線任何資料庫**，也不需要 `.env`。
