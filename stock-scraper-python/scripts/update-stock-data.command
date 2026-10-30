#!/bin/bash
# 在 Finder 點兩下即可手動更新股票資料（開啟終端機視窗顯示進度，結束後按任意鍵關閉）。
# 只能在這台電腦、你的帳號下執行：需要 stock-scraper-python/.env 中的資料庫連線資訊。
# 可從桌面建立捷徑（見 README「手動一鍵更新」），捷徑會自動找到這個檔案的實際位置。

SCRIPT_DIR="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"

# 結果說明（例如「更新完成。」）由 run_daily.sh 顯示在最後一行
"${SCRIPT_DIR}/run_daily.sh"

echo
echo "完整紀錄：${SCRIPT_DIR%/scripts}/logs/daily-$(date +%Y-%m).log"
echo
read -n 1 -s -r -p "按任意鍵關閉視窗…"
echo
