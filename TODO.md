# 待辦清單

## 延後實作

- [ ] **每日自動排程（macOS launchd）**
  - 腳本已完成並驗證過 plist 格式：`stock-scraper-python/scripts/install_schedule.sh`
  - 目前改用手動一鍵更新：`stock-scraper-python/scripts/update-stock-data.command`
  - 啟用方式：`cd stock-scraper-python && ./scripts/install_schedule.sh`（週一至週五 15:00，台股收盤後）
  - 移除方式：`./scripts/install_schedule.sh uninstall`
  - 注意：排定時間若電腦在睡眠中會於喚醒後補執行；關機則該次略過

## 安全

- [ ] 定期更換資料庫密碼（Neon Console → Roles → Reset password），並同步更新：
  - `stock-scraper-python/.env`
  - IntelliJ Run Configuration 的 `DB_PASSWORD`

## 版本控制

- [ ] 將目前的變更 commit（`.env`、`logs/`、`node_modules/` 已被 `.gitignore` 排除）

## 可選調整

- [ ] MACD 計算基準：目前使用收盤價（國際標準）；若與券商軟體差異大，可改為 DI =（最高 + 最低 + 2 × 收盤）/ 4
- [ ] 個股頁記住指標顯示設定（目前重新整理後恢復預設）
- [ ] 測試專用資料庫：Java 的 `contextLoads` 測試目前連線正式使用的 Neon 資料庫（只讀取）；
  若要在 CI（例如 GitHub Actions）執行，可改用 Neon 分支（branch）或 Testcontainers，避免使用正式資料庫密碼
