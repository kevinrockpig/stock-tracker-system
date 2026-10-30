#!/bin/bash
# macOS launchd 每日排程（只影響目前使用者，不需要 sudo）。
#
#   ./scripts/install_schedule.sh            安裝（或更新設定）：週一至週五 15:00 執行 scripts/run_daily.sh --scheduled
#   ./scripts/install_schedule.sh status     查看是否已安裝、上次執行結果與最近的紀錄
#   ./scripts/install_schedule.sh run        立即在背景執行一次（測試用，結果以通知顯示）
#   ./scripts/install_schedule.sh uninstall  移除
#
# 使用 launchd 而非 cron：排定時間若電腦在睡眠中，launchd 會在喚醒後補執行一次，cron 則會直接跳過。
# 關機或登出時不會執行，也不會在開機後補執行；觀察名單每次都抓近兩年資料，下次執行就會補齊，
# 官方開放資料則由 GitHub Actions 每天自動累積（見 deploy/data-archive-repo/）。

set -euo pipefail

LABEL="com.stocktracker.daily-scraper"
PLIST="${HOME}/Library/LaunchAgents/${LABEL}.plist"
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DOMAIN="gui/$(id -u)"

case "${1:-install}" in
  uninstall)
    launchctl bootout "${DOMAIN}" "${PLIST}" 2>/dev/null || true
    rm -f "${PLIST}"
    echo "已移除排程：${LABEL}"
    exit 0
    ;;
  status)
    if ! launchctl print "${DOMAIN}/${LABEL}" >/dev/null 2>&1; then
      echo "排程未安裝（安裝：./scripts/install_schedule.sh）"
      exit 0
    fi
    echo "排程已安裝：週一至週五 15:00（設定檔 ${PLIST}）"
    launchctl print "${DOMAIN}/${LABEL}" | grep -E '^[[:space:]]*(state|runs|last exit code) =' | sed 's/^[[:space:]]*/  /' || true
    LOG_FILE="${PROJECT_DIR}/logs/daily-$(date +%Y-%m).log"
    if [[ -f "${LOG_FILE}" ]]; then
      echo "最近的執行結果（${LOG_FILE}）："
      grep -E '^===== .* (開始|結束)' "${LOG_FILE}" | tail -6 | sed 's/^/  /' || true
    fi
    exit 0
    ;;
  run)
    launchctl kickstart "${DOMAIN}/${LABEL}"
    echo "已在背景開始執行，完成後會顯示通知；進度可看 logs/daily-$(date +%Y-%m).log"
    exit 0
    ;;
  install) ;;
  *)
    echo "用法：$0 [install|status|run|uninstall]" >&2
    exit 64
    ;;
esac

if [[ ! -x "${PROJECT_DIR}/venv/bin/python" ]]; then
  echo "找不到 ${PROJECT_DIR}/venv/bin/python，請先建立虛擬環境並安裝套件" >&2
  exit 1
fi
if [[ ! -f "${PROJECT_DIR}/.env" ]]; then
  echo "找不到 ${PROJECT_DIR}/.env，請先設定資料庫連線（見 README）" >&2
  exit 1
fi

chmod 700 "${PROJECT_DIR}/scripts/run_daily.sh"
mkdir -p "${HOME}/Library/LaunchAgents" "${PROJECT_DIR}/logs"

# 週一（1）至週五（5）15:00 各執行一次（台股 13:30 收盤，證交所約 14:00 更新當日資料）
INTERVALS=""
for weekday in 1 2 3 4 5; do
  INTERVALS+="
    <dict>
      <key>Weekday</key><integer>${weekday}</integer>
      <key>Hour</key><integer>15</integer>
      <key>Minute</key><integer>0</integer>
    </dict>"
done

cat > "${PLIST}" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>${LABEL}</string>
  <key>ProgramArguments</key>
  <array>
    <string>/bin/bash</string>
    <string>${PROJECT_DIR}/scripts/run_daily.sh</string>
    <string>--scheduled</string>
  </array>
  <key>StartCalendarInterval</key>
  <array>${INTERVALS}
  </array>
  <!-- 新建立的檔案只有自己的帳號能讀（077） -->
  <key>Umask</key><integer>63</integer>
  <!-- run_daily.sh 已將輸出寫入 logs/daily-YYYY-MM.log；這裡只記錄 launchd 本身的錯誤 -->
  <key>StandardErrorPath</key><string>${PROJECT_DIR}/logs/launchd-error.log</string>
</dict>
</plist>
EOF
chmod 600 "${PLIST}"
plutil -lint "${PLIST}" >/dev/null

launchctl bootout "${DOMAIN}" "${PLIST}" 2>/dev/null || true
launchctl bootstrap "${DOMAIN}" "${PLIST}"
echo "已安裝排程：${LABEL}（週一至週五 15:00）"
echo "設定檔：${PLIST}"
echo "立即測試一次：./scripts/install_schedule.sh run"
echo "查看狀態：    ./scripts/install_schedule.sh status"
