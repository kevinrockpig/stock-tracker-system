#!/bin/bash
# 安裝 / 移除 macOS launchd 每日排程（只影響目前使用者，不需要 sudo）。
#
#   ./scripts/install_schedule.sh            安裝：週一至週五 15:00 執行 scripts/run_daily.sh
#   ./scripts/install_schedule.sh uninstall  移除
#
# 使用 launchd 而非 cron：排定時間若電腦在睡眠中，launchd 會在喚醒後補執行，cron 則會直接跳過。

set -euo pipefail

LABEL="com.stocktracker.daily-scraper"
PLIST="${HOME}/Library/LaunchAgents/${LABEL}.plist"
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

if [[ "${1:-}" == "uninstall" ]]; then
  launchctl bootout "gui/$(id -u)" "${PLIST}" 2>/dev/null || true
  rm -f "${PLIST}"
  echo "已移除排程：${LABEL}"
  exit 0
fi

if [[ ! -x "${PROJECT_DIR}/venv/bin/python" ]]; then
  echo "找不到 ${PROJECT_DIR}/venv/bin/python，請先建立虛擬環境並安裝套件" >&2
  exit 1
fi

chmod +x "${PROJECT_DIR}/scripts/run_daily.sh"
mkdir -p "${HOME}/Library/LaunchAgents" "${PROJECT_DIR}/logs"

# 週一（1）至週五（5）15:00 各執行一次
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
  </array>
  <key>StartCalendarInterval</key>
  <array>${INTERVALS}
  </array>
  <!-- run_daily.sh 已將輸出寫入 logs/daily-YYYY-MM.log；這裡只記錄 launchd 本身的錯誤 -->
  <key>StandardErrorPath</key><string>${PROJECT_DIR}/logs/launchd-error.log</string>
</dict>
</plist>
EOF

launchctl bootout "gui/$(id -u)" "${PLIST}" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "${PLIST}"
echo "已安裝排程：${LABEL}（週一至週五 15:00）"
echo "設定檔：${PLIST}"
echo "立即測試一次：launchctl kickstart gui/$(id -u)/${LABEL}"
