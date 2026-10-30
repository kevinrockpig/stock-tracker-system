#!/bin/bash
# 每日更新，輸出附加到 logs/daily-YYYY-MM.log：
#   1. yfinance 擷取觀察名單日 K → 計算技術指標 → 掃描策略（python -m scraper.main）
#   2. 累積證交所 / 櫃買中心的官方開放資料（python -m scraper.official）
# 兩個步驟互相獨立：第 1 步失敗仍會執行第 2 步（官方資料只提供當天，漏抓無法補回）。
#
# 手動執行：./scripts/run_daily.sh，或在 Finder 點兩下 scripts/update-stock-data.command
# 自動排程：scripts/install_schedule.sh（目前延後實作，見專案根目錄 TODO.md）
# 休市日（國定假日、颱風假）執行也不會出錯：沒有新的交易日，結果維持前一個交易日。
#
# exit code：0 成功、1 部分股票或來源失敗、2 設定錯誤、3 資料庫錯誤、75 已有更新正在執行
#（兩個步驟都失敗時，回傳數字較大的那個）

set -u
cd "$(dirname "$0")/.." || exit 1
umask 077 # 新建立的 log 只有自己的帳號能讀

mkdir -p logs
LOG_FILE="logs/daily-$(date +%Y-%m).log"

# 避免重複執行（例如連點兩次，或手動與排程同時觸發）；mkdir 為原子操作，可當作鎖
LOCK_DIR="logs/.run.lock"
if ! mkdir "${LOCK_DIR}" 2>/dev/null; then
  echo "已有更新正在執行中。若確定沒有在執行（例如上次被強制關閉），請刪除 ${PWD}/${LOCK_DIR} 後再試。" >&2
  exit 75
fi
trap 'rmdir "${LOCK_DIR}"' EXIT

run() {
  echo "===== $(date '+%Y-%m-%d %H:%M:%S') 開始 ====="
  echo "----- 1/2 觀察名單（yfinance）-----"
  ./venv/bin/python -m scraper.main
  local main_status=$?
  echo "----- 2/2 官方開放資料累積（證交所 / 櫃買中心）-----"
  ./venv/bin/python -m scraper.official
  local official_status=$?
  local status=$(( main_status > official_status ? main_status : official_status ))
  echo "===== $(date '+%Y-%m-%d %H:%M:%S') 結束（exit ${status}）====="
  echo
  return "${status}"
}

if [[ -t 1 ]]; then
  # 在終端機執行：同時顯示在畫面上並寫入 log
  run 2>&1 | tee -a "${LOG_FILE}"
  exit "${PIPESTATUS[0]}"
else
  # 排程或背景執行：只寫入 log
  run >> "${LOG_FILE}" 2>&1
fi
