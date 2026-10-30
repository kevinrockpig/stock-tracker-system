#!/bin/bash
# 每日更新，輸出附加到 logs/daily-YYYY-MM.log：
#   1. yfinance 擷取觀察名單日 K → 計算技術指標 → 掃描策略 → 回測 → 參考價位（python -m scraper.main）
#   2. 累積證交所 / 櫃買中心的官方開放資料（python -m scraper.official）
# 兩個步驟互相獨立：第 1 步失敗仍會執行第 2 步（官方資料只提供當天，漏抓無法補回）。
#
# 手動執行：./scripts/run_daily.sh，或在 Finder 點兩下 scripts/update-stock-data.command
# 自動排程：scripts/install_schedule.sh 安裝後，由 launchd 以 --scheduled 執行：
#   - 先等網路連線（剛從睡眠喚醒時 Wi-Fi 可能還沒連上），最多等 NETWORK_WAIT_SECONDS 秒（預設 60）
#   - 結束後以 macOS 通知顯示結果
# 休市日（國定假日、颱風假）執行也不會出錯：沒有新的交易日，結果維持前一個交易日。
#
# exit code：0 成功、1 部分股票或來源失敗、2 設定錯誤、3 資料庫錯誤、75 已有更新正在執行
#（兩個步驟都失敗時，回傳數字較大的那個）

set -u
cd "$(dirname "$0")/.." || exit 1
umask 077 # 新建立的 log 只有自己的帳號能讀

SCHEDULED=false
[[ "${1:-}" == "--scheduled" ]] && SCHEDULED=true

mkdir -p logs
LOG_FILE="logs/daily-$(date +%Y-%m).log"

status_message() {
  case $1 in
    0) echo "更新完成。" ;;
    1) echo "部分股票或官方資料來源更新失敗，請查看紀錄。" ;;
    2) echo "設定錯誤：請檢查 stock-scraper-python/.env。" ;;
    3) echo "資料庫連線或結構錯誤，請查看紀錄。" ;;
    75) echo "已有更新正在執行中，這次沒有執行。" ;;
    *) echo "發生未預期的錯誤（exit $1），請查看紀錄。" ;;
  esac
}

# macOS 通知（只在排程執行時使用；訊息為固定文字，不含外部輸入）
notify() {
  local status=$1
  local title="股票資料更新"
  # 75（已有更新在執行）不算失敗
  [[ ${status} -ne 0 && ${status} -ne 75 ]] && title="股票資料更新失敗"
  local output
  if output=$(osascript -e "display notification \"$(status_message "${status}")\" with title \"${title}\"" 2>&1); then
    echo "已送出通知：${title}" >> "${LOG_FILE}"
  else
    echo "無法送出通知：${output}" >> "${LOG_FILE}"
  fi
}

finish() {
  local status=$1
  ${SCHEDULED} && notify "${status}"
  exit "${status}"
}

# 避免重複執行（例如連點兩次，或手動與排程同時觸發）；mkdir 為原子操作，可當作鎖。
# 鎖裡記錄執行中的程序編號：若該程序已不存在（例如執行中關機、被強制結束），視為殘留的鎖並清除，
# 否則排程之後每天都會被這個鎖擋下。
LOCK_DIR="logs/.run.lock"
acquire_lock() {
  if mkdir "${LOCK_DIR}" 2>/dev/null; then
    echo $$ > "${LOCK_DIR}/pid"
    return 0
  fi
  local pid
  pid=$(cat "${LOCK_DIR}/pid" 2>/dev/null)
  # 程序編號可能被其他程式重複使用，所以同時確認它確實是 run_daily.sh
  if [[ -n "${pid}" ]] && ps -p "${pid}" -o command= 2>/dev/null | grep -q run_daily.sh; then
    return 1
  fi
  echo "清除殘留的執行鎖（記錄的程序 ${pid:-未知} 已不在執行更新）" >> "${LOG_FILE}"
  rm -rf "${LOCK_DIR}"
  mkdir "${LOCK_DIR}" 2>/dev/null && echo $$ > "${LOCK_DIR}/pid"
}

if ! acquire_lock; then
  status_message 75 >&2
  finish 75
fi
trap 'rm -rf "${LOCK_DIR}"' EXIT

wait_for_network() {
  local limit=${NETWORK_WAIT_SECONDS:-60} waited=0
  until curl -s -o /dev/null --max-time 5 https://www.twse.com.tw; do
    if (( waited >= limit )); then
      echo "等候 ${limit} 秒仍無法連線網路，照常執行（失敗的項目會列在下方）"
      return
    fi
    sleep 5
    waited=$(( waited + 5 ))
  done
  (( waited > 0 )) && echo "等候網路連線 ${waited} 秒"
}

run() {
  echo "===== $(date '+%Y-%m-%d %H:%M:%S') 開始$(${SCHEDULED} && echo '（排程）') ====="
  ${SCHEDULED} && wait_for_network
  echo "----- 1/2 觀察名單（yfinance）-----"
  ./venv/bin/python -m scraper.main
  local main_status=$?
  echo "----- 2/2 官方開放資料累積（證交所 / 櫃買中心）-----"
  ./venv/bin/python -m scraper.official
  local official_status=$?
  local status=$(( main_status > official_status ? main_status : official_status ))
  echo "===== $(date '+%Y-%m-%d %H:%M:%S') 結束（exit ${status}）：$(status_message "${status}") ====="
  echo
  return "${status}"
}

if [[ -t 1 ]]; then
  # 在終端機執行：同時顯示在畫面上並寫入 log
  run 2>&1 | tee -a "${LOG_FILE}"
  finish "${PIPESTATUS[0]}"
else
  # 排程或背景執行：只寫入 log
  run >> "${LOG_FILE}" 2>&1
  finish $?
fi
