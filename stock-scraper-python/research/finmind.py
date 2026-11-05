"""R2-1 研究用的外部資料：月營收與三大法人買賣超（FinMind），只存在本機、只供研究。

    python -m research.finmind            # 下載（已下載的跳過，可中斷後接續）
    python -m research.finmind --check    # 只檢查已下載的資料

來源與條款（2026-11-05 查證）：
- 證交所網站使用條款第 6 條禁止未經同意以程式下載網站資料；公開資訊觀測站 robots.txt 為 Disallow: /；
  政府開放資料只有「當下」一期，上市個股法人買賣超沒有開放資料。因此歷史資料改用 FinMind（使用者 2026-11-05 決定）
- FinMind 條款：內容供教育與參考；不得把原始資料再提供給第三方或做鏡像服務；商業用途需付費方案；
  資料來源為證交所。所以原始資料只放在 research/cache/finmind/（不進 Git），網站只呈現研究得出的規則
- 未註冊每小時 300 次請求：每次間隔 13 秒；短時間大量錯誤會被暫時封鎖，遇到錯誤就停止

月營收「何時可以用」：法定申報期限為次月 10 日，回測一律視為 10 日之後的第一個交易日才知道（保守，避免偷看未來）。
"""

import argparse
import json
import logging
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict
from dataclasses import dataclass
from datetime import date
from pathlib import Path

from research import cache
from research.data import ETF

logger = logging.getLogger(__name__)

API = "https://api.finmindtrade.com/api/v4/data"
RAW_DIR = cache.CACHE_PATH.parent / "finmind"
START = "2015-01-01"
INTERVAL = 13.0  # 秒；每小時最多約 277 次
OPEN_DATA_REVENUE = "https://openapi.twse.com.tw/v1/opendata/t187ap05_L"  # 政府開放資料：上市公司最新一個月營收（核對用）

# 避險類股（事件研究用，執行前固定）：上市的金融保險、航運、食品、油電燃氣，以及電信三雄
DEFENSIVE_INDUSTRIES = ["金融保險", "航運業", "食品工業", "油電燃氣業"]
TELECOM = ["2412", "3045", "4904"]
INVESTORS = {  # FinMind 名稱 → 研究用的類別
    "Foreign_Investor": "foreign", "Foreign_Dealer_Self": "foreign",
    "Investment_Trust": "trust", "Dealer_self": "dealer", "Dealer_Hedging": "dealer",
}


def stock_id(symbol: str) -> str:
    return symbol.split(".")[0]


def _path(dataset: str, data_id: str | None) -> Path:
    return RAW_DIR / dataset / f"{data_id or 'all'}.json"


def _fetch(dataset: str, data_id: str | None, end: str) -> list[dict]:
    # 股票清單不帶日期：帶了會依「資料更新日」過濾，只剩一部分股票
    params = {"dataset": dataset} if dataset == "TaiwanStockInfo" else {"dataset": dataset, "start_date": START, "end_date": end}
    if data_id:
        params["data_id"] = data_id
    url = f"{API}?{urllib.parse.urlencode(params)}"
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "stock-tracker-research"}), timeout=120) as response:
        body = json.load(response)
    if body.get("status") != 200:
        raise RuntimeError(f"{dataset} {data_id}：{body.get('status')} {body.get('msg')}")
    return body["data"]


def jobs(data: cache.MarketData, info: list[dict]) -> list[tuple[str, str | None]]:
    """要下載的項目（不含 TaiwanStockInfo）。"""
    watch = [stock_id(s) for s in data.stocks["symbol"]]
    defensive = defensive_stocks(info)
    out = [("TaiwanStockTotalInstitutionalInvestors", None)]
    out += [("TaiwanStockMonthRevenue", s) for s in watch if s != stock_id(ETF)]
    out += [("TaiwanStockInstitutionalInvestorsBuySell", s) for s in watch]
    others = sorted({s for ids in defensive.values() for s in ids} - set(watch))
    out += [("TaiwanStockInstitutionalInvestorsBuySell", s) for s in others]
    out += [("TaiwanStockPrice", s) for s in sorted({s for ids in defensive.values() for s in ids})]
    return out


def defensive_stocks(info: list[dict]) -> dict[str, list[str]]:
    """類股 → 上市股票代號（4 碼數字，FinMind 目前的產業分類；已下市的不在其中）。"""
    groups = defaultdict(set)
    for row in info:
        sid = row["stock_id"]
        if row["type"] == "twse" and sid.isdigit() and len(sid) == 4 and row["industry_category"] in DEFENSIVE_INDUSTRIES:
            groups[row["industry_category"]].add(sid)
    groups["電信"] = set(TELECOM)
    return {k: sorted(v) for k, v in groups.items()}


def download(data: cache.MarketData) -> int:
    end = data.as_of.isoformat()
    info_path = _path("TaiwanStockInfo", None)
    if not info_path.exists():
        _save(info_path, _fetch("TaiwanStockInfo", None, end))
        time.sleep(INTERVAL)
    info = json.loads(info_path.read_text(encoding="utf-8"))
    if len(info) < 3000:  # 上市櫃與興櫃合計約 4,000 筆
        logger.error("股票清單只有 %d 筆，可能不完整；請刪除 %s 後重新執行", len(info), info_path)
        return 3
    todo = [(d, i) for d, i in jobs(data, info) if not _path(d, i).exists()]
    logger.info("共 %d 項，還要下載 %d 項（約 %.0f 分鐘）", len(jobs(data, info)), len(todo), len(todo) * INTERVAL / 60)
    for n, (dataset, data_id) in enumerate(todo, 1):
        try:
            rows = _fetch(dataset, data_id, end)
        except (urllib.error.URLError, RuntimeError, OSError, json.JSONDecodeError) as exc:
            logger.error("下載失敗，先停止（避免連續錯誤被封鎖），稍後重新執行會從這裡接續：%s", exc)
            return 3
        _save(_path(dataset, data_id), rows)
        if n % 20 == 0 or n == len(todo):
            logger.info("已下載 %d / %d", n, len(todo))
        if n < len(todo):
            time.sleep(INTERVAL)
    return 0


def _save(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(rows, ensure_ascii=False), encoding="utf-8")
    tmp.replace(path)


def _load(dataset: str, data_id: str | None) -> list[dict]:
    path = _path(dataset, data_id)
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else []


# ---- 讀取（研究程式使用）----

@dataclass
class Revenue:
    year: int
    month: int
    revenue: float  # 元
    deadline: date  # 法定申報期限（次月 10 日）；回測時視為之後第一個交易日才可用


@dataclass
class External:
    revenue: dict[str, list[Revenue]]  # 代號（4 碼）→ 依年月排序
    flows: dict[str, dict[date, dict[str, int]]]  # 代號 → 日期 → foreign / trust / dealer 買賣超股數
    prices: dict[str, dict[date, float]]  # 避險類股的收盤價（未調整，換算買賣超金額用）
    market: dict[date, dict[str, float]]  # 全市場三大法人買賣超金額（元）
    defensive: dict[str, list[str]]  # 類股 → 代號
    names: dict[str, str]


def deadline_of(year: int, month: int) -> date:
    return date(year + (month == 12), month % 12 + 1, 10)


def load() -> External:
    info = _load("TaiwanStockInfo", None)
    if not info:
        raise FileNotFoundError(f"沒有 FinMind 資料 {RAW_DIR}，請先執行 python -m research.finmind")
    revenue, flows, prices = {}, {}, {}
    for path in sorted((RAW_DIR / "TaiwanStockMonthRevenue").glob("*.json")):
        rows = json.loads(path.read_text(encoding="utf-8"))
        revenue[path.stem] = sorted((Revenue(r["revenue_year"], r["revenue_month"], float(r["revenue"]), deadline_of(r["revenue_year"], r["revenue_month"]))
                                     for r in rows), key=lambda r: (r.year, r.month))
    for path in sorted((RAW_DIR / "TaiwanStockInstitutionalInvestorsBuySell").glob("*.json")):
        days = defaultdict(lambda: {"foreign": 0, "trust": 0, "dealer": 0})
        for r in json.loads(path.read_text(encoding="utf-8")):
            kind = INVESTORS.get(r["name"])
            if kind:
                days[date.fromisoformat(r["date"])][kind] += int(r["buy"]) - int(r["sell"])
        flows[path.stem] = dict(sorted(days.items()))
    for path in sorted((RAW_DIR / "TaiwanStockPrice").glob("*.json")):
        prices[path.stem] = {date.fromisoformat(r["date"]): float(r["close"]) for r in json.loads(path.read_text(encoding="utf-8")) if r["close"]}
    market = defaultdict(lambda: {"foreign": 0.0, "trust": 0.0, "dealer": 0.0})
    for r in _load("TaiwanStockTotalInstitutionalInvestors", None):
        kind = INVESTORS.get(r["name"])
        if kind:
            market[date.fromisoformat(r["date"])][kind] += float(r["buy"]) - float(r["sell"])
    names = {r["stock_id"]: r["stock_name"] for r in info}
    return External(revenue, flows, prices, dict(sorted(market.items())), defensive_stocks(info), names)


# ---- 檢查 ----

def check(data: cache.MarketData) -> list[str]:
    """完整性與正確性：筆數與期間、法人日期是否都是交易日、最新一個月營收與政府開放資料一致。"""
    ext = load()
    trading = set(data.bars[ETF]["trade_date"])
    lines = [f"月營收 {len(ext.revenue)} 檔、法人買賣超 {len(ext.flows)} 檔、避險類股股價 {len(ext.prices)} 檔、全市場法人 {len(ext.market)} 天",
             "避險類股：" + "、".join(f"{k} {len(v)} 檔" for k, v in ext.defensive.items())]
    missing = [s for s in (stock_id(x) for x in data.stocks["symbol"]) if s != stock_id(ETF) and not ext.revenue.get(s)]
    short = {s: len(v) for s, v in ext.revenue.items() if len(v) < 100}
    lines.append(f"月營收：缺 {missing or '無'}；少於 100 個月的 {short or '無'}")
    gaps = 0
    for s, months in ext.revenue.items():
        keys = [(r.year, r.month) for r in months]
        for (y1, m1), (y2, m2) in zip(keys, keys[1:]):
            gaps += (y2 * 12 + m2) - (y1 * 12 + m1) != 1
    lines.append(f"月營收中間缺月：{gaps} 處")
    start = min(trading)
    off = sorted({d for days in ext.flows.values() for d in days if d not in trading and start <= d <= data.as_of})
    first = min(next(iter(days)) for days in ext.flows.values() if days)
    lines.append(f"法人買賣超：最早 {first}（股價從 {start} 起）；股價期間內有法人資料、但 0050 沒有日 K 的日子 {len(off)} 天："
                 f"{', '.join(map(str, off))}（2025-06-11～17 為 0050 分割停牌，其餘為 Yahoo 缺漏，例如週六補行交易日）")
    watch_days = {s: len(ext.flows.get(stock_id(x), {})) for x in data.stocks["symbol"] for s in [stock_id(x)]}
    lines.append(f"觀察名單法人資料天數：最少 {min(watch_days.values())}（{min(watch_days, key=watch_days.get)}）、最多 {max(watch_days.values())}")

    # 最新一個月營收對政府開放資料（千元）
    try:
        with urllib.request.urlopen(urllib.request.Request(OPEN_DATA_REVENUE, headers={"User-Agent": "stock-tracker-research"}), timeout=60) as response:
            official = json.load(response)
    except (urllib.error.URLError, OSError, json.JSONDecodeError) as exc:
        lines.append(f"無法取得開放資料核對：{exc}")
        return lines
    compared, diffs = 0, []
    for row in official:
        sid = row["公司代號"]
        months = ext.revenue.get(sid)
        if not months:
            continue
        roc = row["資料年月"]
        y, m = int(roc[:-2]) + 1911, int(roc[-2:])
        mine = next((r.revenue for r in months if (r.year, r.month) == (y, m)), None)
        theirs = float(row["營業收入-當月營收"]) * 1000
        compared += 1
        if mine is None or abs(mine - theirs) > 1000:
            diffs.append(f"{sid} {y}-{m:02d} FinMind {mine} vs 開放資料 {theirs:.0f}")
    lines.append(f"最新月營收與政府開放資料比對 {compared} 檔，不一致 {len(diffs)} 檔" + (f"：{diffs}" if diffs else ""))
    return lines


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-7s %(message)s", datefmt="%H:%M:%S")
    parser = argparse.ArgumentParser(description="研究用的外部資料（FinMind）")
    parser.add_argument("--check", action="store_true", help="只檢查已下載的資料")
    args = parser.parse_args()
    data = cache.load()
    if not args.check:
        code = download(data)
        if code:
            return code
    try:
        for line in check(data):
            logger.info(line)
    except FileNotFoundError as exc:
        logger.error("%s", exc)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
