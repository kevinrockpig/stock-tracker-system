"""股本事件的人工校正：Yahoo 已換算價格、卻沒有提供事件紀錄的分割（或配股、減資）。

Yahoo 的價格會依股數變動換算，但 stock_actions 只能記錄 Yahoo 回傳的事件；漏掉時，
庫存依買進日換算股數（Java HoldingService）會少乘這個比例。這裡列出查證過的事件，每次寫入事件時一併補上。

新增一筆前要先查證（官方公告或兩個以上獨立來源），並確認資料庫價格在事件前後已按比例換算。
找漏掉的事件：verify.sql 查詢 27（停牌期間沒有股數變動紀錄）。
"""

import logging
from dataclasses import dataclass
from datetime import date, timedelta
from decimal import Decimal

logger = logging.getLogger("scraper")


@dataclass(frozen=True)
class ShareCorrection:
    symbol: str
    ex_date: date  # 以新股數開始交易的第一天
    split_ratio: Decimal  # 新股數 / 舊股數（與 stock_actions.split_ratio 相同）
    source: str  # 查證依據


SHARE_CORRECTIONS = (
    ShareCorrection(
        "0050.TW", date(2025, 6, 18), Decimal("4"),
        "1 拆 4：2025-06-10 為分割前最後交易日（收盤 188.65），06-11～06-17 暫停交易，06-18 恢復交易；"
        "資料庫 06-10 收盤 47.1625 = 188.65 ÷ 4（TVBS、科技新報、經濟日報、數位時代）",
    ),
)

# Yahoo 日後補上同一個事件時，日期可能與這裡不同（例如記在停牌第一天）；前後這幾天內已有股數變動就不再補，避免重複換算
DUPLICATE_WINDOW = timedelta(days=30)


def corrections_for(symbol: str) -> list[ShareCorrection]:
    return [c for c in SHARE_CORRECTIONS if c.symbol == symbol]


def apply_share_corrections(symbol: str, records: list[dict], stored_dates) -> list[dict]:
    """在 Yahoo 的事件紀錄（records）中補上人工校正，回傳新的清單（不修改傳入的資料）。

    stored_dates：資料庫中這檔股票已有股數變動（split_ratio 不為空）的日期。
    Yahoo（本次抓到的或資料庫中的）在前後 DUPLICATE_WINDOW 內已有其他日期的股數變動時，略過並提醒移除這筆校正。
    """
    result = [dict(r) for r in records]
    for correction in corrections_for(symbol):
        yahoo_dates = {r["ex_date"] for r in result if r["split_ratio"] is not None} | set(stored_dates)
        nearby = sorted(
            d for d in yahoo_dates
            if d != correction.ex_date and abs(d - correction.ex_date) <= DUPLICATE_WINDOW
        )
        if nearby:
            logger.warning(
                "[%s] Yahoo 已有 %s 的股數變動，略過 %s 的人工校正（確認是同一事件後，請從 corrections.py 移除）",
                symbol, "、".join(map(str, nearby)), correction.ex_date,
            )
            continue
        same_day = next((r for r in result if r["ex_date"] == correction.ex_date), None)
        if same_day is None:
            result.append({"symbol": symbol, "ex_date": correction.ex_date,
                           "cash_dividend": None, "split_ratio": correction.split_ratio})
        elif same_day["split_ratio"] is None:
            same_day["split_ratio"] = correction.split_ratio  # 同一天 Yahoo 只有現金股利
        elif same_day["split_ratio"] != correction.split_ratio:
            logger.warning(
                "[%s] Yahoo 在 %s 的股數變動為 %s，與人工校正 %s 不同，以 Yahoo 為準（請重新查證）",
                symbol, correction.ex_date, same_day["split_ratio"], correction.split_ratio,
            )
    return result
