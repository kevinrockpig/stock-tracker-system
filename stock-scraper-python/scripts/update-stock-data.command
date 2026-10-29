#!/bin/bash
# 在 Finder 點兩下即可手動更新股票資料（開啟終端機視窗顯示進度，結束後按任意鍵關閉）。
# 只能在這台電腦、你的帳號下執行：需要 stock-scraper-python/.env 中的資料庫連線資訊。
# 可從桌面建立捷徑（見 README「手動一鍵更新」），捷徑會自動找到這個檔案的實際位置。

SCRIPT_DIR="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"

"${SCRIPT_DIR}/run_daily.sh"
status=$?

echo
case ${status} in
  0) echo "更新完成。" ;;
  1) echo "部分股票更新失敗，請查看上方訊息。" ;;
  2) echo "設定錯誤：請檢查 stock-scraper-python/.env。" ;;
  3) echo "資料庫連線或結構錯誤，請查看上方訊息。" ;;
  75) echo "已有更新正在執行中，這次沒有執行。" ;;
  *) echo "發生未預期的錯誤（exit ${status}），請查看上方訊息。" ;;
esac
echo "完整紀錄：${SCRIPT_DIR%/scripts}/logs/daily-$(date +%Y-%m).log"
echo
read -n 1 -s -r -p "按任意鍵關閉視窗…"
echo
