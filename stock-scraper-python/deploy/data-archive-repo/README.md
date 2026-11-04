# stock-data-archive（私人）

股票追蹤系統的雲端每日排程（GitHub Actions），以及證交所 / 櫃買中心開放資料的每日備份。
程式碼在公開 repo [stock-tracker-system](https://github.com/kevinrockpig/stock-tracker-system) 的 `stock-scraper-python/`。

**請保持這個 repo 為私人**：Actions 執行紀錄會出現資料庫主機、庫存中的股票代號等資訊。

## 內容

```
archive/<來源>/<年>/<日期>.csv.gz
```

| 來源 | 內容 | 檔名日期 |
|---|---|---|
| `twse_csv`、`twse_openapi` | 證交所 上市個股日成交 | 資料的交易日 |
| `tpex_openapi` | 櫃買中心 上櫃個股日成交 | 資料的交易日 |
| `twse_actions`、`tpex_actions` | 除權除息預告表 | 抓取當天（當天看到的整份預告表） |

- 保留官方原始欄位，可直接用 Excel 開啟或重新匯入
- 資料授權：政府資料開放授權條款－第 1 版（https://data.gov.tw/license）

## 排程

| 設定檔 | 內容 | 時間（台灣） |
|---|---|---|
| [official-daily.yml](.github/workflows/official-daily.yml) | 官方開放資料累積＋備份檔 commit | 週二到週六 08:17、11:47（備援） |
| [market-daily.yml](.github/workflows/market-daily.yml) | 觀察名單與庫存中的股票：日 K、指標、策略、回測、交易計畫、參考價位，接著做資料健康檢查 | 週一到週五 15:40、18:10（備援） |

兩者使用相同的 Secrets。GitHub 的排程可能延遲或偶爾被略過，所以各排兩次；重複執行只會更新、不會產生重複資料。

### 行情每日更新（market-daily.yml）

- 取代 Mac 的每日排程：電腦關機也會更新。剛啟用時與 Mac 並行一週（Mac 15:00、這裡 15:40，時間錯開），
  確認 Yahoo 不會擋 GitHub 的連線、每天都成功後，再在 Mac 執行 `./scripts/install_schedule.sh uninstall` 停用
- 失敗或資料健康檢查發現異常時，GitHub 寄信通知；檢查結果顯示在該次執行頁面的 Summary
- 最後寄出每日通知（設定了 `MAIL_USERNAME` 才執行）：每個交易日每人一封，重跑不會重寄
- 手動執行：Actions → 行情每日更新 → Run workflow
- 18:10 的備援先檢查：今天每檔都已有日 K 與參考價位（15:40 已成功）就直接結束；有任何一檔缺資料才重新執行
- 用量：
  - GitHub Actions：完整執行約 10 分鐘（含安裝套件），備援略過時約 1 分鐘，每月約 250 分鐘，
    加上官方資料累積，在 GitHub Free 私人 repo 每月 2,000 分鐘的額度內
  - Neon 網路傳輸（免費每月 5 GB）：完整執行一次約從資料庫讀出 18 MB（2026-11-04 實測），每月約 0.4 GB

### 官方資料每日累積（official-daily.yml）

- 週二到週六，台灣時間 08:17 與 11:47（備援）各執行一次，抓前一個交易日
- 同時寫入資料庫（Neon）並 commit 備份檔；內容沒變就不會 commit
- 手動執行：Actions → 官方資料每日累積 → Run workflow
- 失敗時 GitHub 會寄信通知；可手動重跑，或在本機點一鍵更新（`update-stock-data.command`）補抓。
  官方只提供最新一天的資料，**當天沒補抓就無法補回**

## Secrets

Settings → Secrets and variables → Actions → Repository secrets：

| 名稱 | 內容 |
|---|---|
| `DB_URL` | 與本機 `.env` 相同，例如 `jdbc:postgresql://<host>/<db>?sslmode=require` |
| `DB_USERNAME` | 資料庫帳號 |
| `DB_PASSWORD` | 資料庫密碼（重設 Neon 密碼後要同步更新這裡） |
| `MAIL_USERNAME` | 寄出每日通知的 Gmail 帳號（market-daily.yml；沒有設定時略過通知） |
| `MAIL_PASSWORD` | 該 Gmail 帳號的「應用程式密碼」（16 碼，需先開啟兩步驟驗證；變更 Google 密碼後會失效，要重新建立） |

選填的 Variables（同一頁的 Variables 分頁）：`SITE_URL`，網站網址，信中附上通知設定的連結（網站上線後再設定）。
