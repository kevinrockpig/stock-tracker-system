# stock-scraper-python

用 yfinance 擷取觀察名單的日行情，經 Pandas 清理後寫入 PostgreSQL（Neon），並計算技術指標、掃描選股策略。
資料表 `stocks`、`stock_prices`、`stock_indicators`、`strategy_signals` 由本專案建立與維護，Java API 只讀取。

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
python -m scraper.main                 # 預設抓 2 年日 K（OHLCV），並計算技術指標
python -m scraper.main --period 5d     # 指定期間：5d、1mo、3mo、1y、2y …
```

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

查詢 4、6、7、11、12 應回傳 0 列；查詢 8（缺漏交易日）有已知例外，列在查詢的註解中；查詢 9 的兩個筆數應相同。

## 技術指標

每次執行在寫入日 K 後，會從資料庫讀回該股票全部日 K，計算指標並寫入 `stock_indicators`（與 `stock_prices` 一對一，外鍵 `ON DELETE CASCADE`）。

| 欄位 | 指標 | 計算方式 |
|---|---|---|
| `ma5` `ma10` `ma20` `ma60` | 移動平均 | TA-Lib `SMA`，以交易日計算 |
| `macd_dif` `macd_signal` `macd_osc` | MACD（12, 26, 9） | TA-Lib `MACD`；台股名稱 DIF / MACD / OSC |
| `rsi6` `rsi12` | RSI | TA-Lib `RSI`（Wilder 平滑） |
| `kd_k` `kd_d` | KD（9, 3, 3） | 自行實作台股公式（1/3 平滑、起始值 50），TA-Lib 的 STOCH 與台股不同 |

MACD、RSI、KD 前 120 筆為暖身期，存為 NULL；因此預設抓兩年，讓最近一年的指標完整。

## 策略掃描

計算指標後，依每個交易日的收盤價與指標判斷策略，結果寫入 `strategy_signals`（只存符合的組合，每次執行與計算結果同步：新增缺少的、刪除不再成立的）。

| 代碼 | 名稱 | 成立條件（「前一日」為前一個交易日） |
|---|---|---|
| `ma_bullish` | 均線多頭排列 | MA5 > MA10 > MA20 > MA60 |
| `golden_cross` | MACD 黃金交叉 | 前一日 DIF ≤ MACD，當日 DIF > MACD（只在交叉當天成立） |
| `break_ma20` | 突破 20 日均線 | 前一日收盤 ≤ MA20，當日收盤 > MA20（只在突破當天成立） |

執行結束時 log 會列出市場最新交易日各策略符合的股票。

## 手動一鍵更新

在 Finder 點兩下 `scripts/update-stock-data.command`：開啟終端機視窗顯示進度，結束後顯示成功或失敗，按任意鍵關閉。
等同於在終端機執行 `./scripts/run_daily.sh`。輸出同時附加到 `logs/daily-YYYY-MM.log`。

建立桌面捷徑（只需做一次）：

```bash
ln -s "$(pwd)/scripts/update-stock-data.command" ~/Desktop/更新股票資料.command
```

- 只能在這台電腦、你的帳號下執行：需要 `.env` 的資料庫連線資訊；網站（Vue / Java）沒有任何觸發入口
- `.env` 應設為只有自己能讀（`chmod 600 .env`），權限過寬時程式會提醒
- 同時只會有一個更新在執行（連點兩次時第二次會直接結束）
- 休市日（國定假日、颱風假）執行不會出錯，結果維持前一個交易日
- 只會對最近 20 個交易日內的缺漏發出警告；更早的已知 yfinance 缺漏列在 `sql/verify.sql` 查詢 8

## 每日自動排程（延後實作，見專案根目錄 TODO.md）

腳本已完成，需要時再啟用：

```bash
./scripts/install_schedule.sh             # 安裝：週一至週五 15:00 自動執行（台股 13:30 收盤後）
./scripts/install_schedule.sh uninstall   # 移除排程
```

使用 macOS launchd 而非 cron：排定時間若電腦在睡眠中，會在喚醒後補執行。

## 單元測試

```bash
pip install -r requirements-dev.txt
python -m pytest
```

測試只使用程式內產生的小型資料，**不連線任何資料庫**，也不需要 `.env`。
