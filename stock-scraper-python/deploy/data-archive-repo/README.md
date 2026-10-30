# stock-data-archive（私人）

證交所 / 櫃買中心開放資料的每日備份，由 GitHub Actions 自動執行並 commit。
程式碼在公開 repo [stock-tracker-system](https://github.com/kevinrockpig/stock-tracker-system) 的 `stock-scraper-python/`。

**請保持這個 repo 為私人**：Actions 執行紀錄會出現資料庫主機等資訊。

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

設定檔：[.github/workflows/official-daily.yml](.github/workflows/official-daily.yml)

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
