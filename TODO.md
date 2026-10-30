# 開發路線圖

## 資料來源

- [x] **每日累積證交所與櫃買中心開放資料**（2026-10-29 開始，`python -m scraper.official`，已併入一鍵更新）
- [ ] **雲端每日排程**：以 GitHub Actions 執行官方資料累積，避免因電腦關機或忘記執行而漏抓（漏抓的日子無法補回）
  - 設定檔已完成：`stock-scraper-python/deploy/data-archive-repo/`，待在私人 repo 啟用
- [x] 累積除權除息預告表（證交所 `TWT48U_ALL`、櫃買 `tpex_exright_prepost`）、CSV 備份、資料庫用量監控（2026-10-29）
- [ ] 價格調整：事件日以「漲跌價差 ≠ 收盤 − 前日收盤」偵測（證交所除權息日漲跌填 0），
  除權息依預告表的現金股利 / 配股比例計算；不在預告表的事件（分割、減資）需另行查證調整比例（`verify.sql` 查詢 17）
- [ ] 視需要將畫面的資料來源由 yfinance 切換為官方資料（網站依授權條款標示資料來源）

## 功能

- [ ] **每日自動排程（macOS launchd）**
  - 腳本已完成：`stock-scraper-python/scripts/install_schedule.sh`
  - 目前使用手動一鍵更新：`stock-scraper-python/scripts/update-stock-data.command`
- [ ] 網站內的使用說明頁與免責聲明（資料性質、指標與策略的定義、「僅供參考，不構成投資建議」）
- [ ] 個股頁記住指標顯示設定（目前重新整理後恢復預設）

## 可選調整

- [ ] MACD 計算基準：目前使用收盤價（國際標準）；若與券商軟體差異大，可改為 DI =（最高 + 最低 + 2 × 收盤）/ 4
- [ ] 測試專用資料庫：Java 的 `contextLoads` 測試目前連線正式資料庫（只讀取）；
  若要在 CI 執行，可改用 Neon 分支（branch）或 Testcontainers
