"""環境變數讀取、資料庫連線 URL 組裝與觀察名單設定。"""

import logging
import os
from datetime import date
from pathlib import Path
from urllib.parse import parse_qsl, urlsplit

from dotenv import load_dotenv
from sqlalchemy.engine import URL

logger = logging.getLogger(__name__)

PROJECT_ROOT = Path(__file__).resolve().parent.parent
ENV_FILE = PROJECT_ROOT / ".env"

# 載入 stock-scraper-python/.env；系統中已存在的環境變數優先，不會被 .env 覆蓋
load_dotenv(ENV_FILE)

# 觀察名單分組：yfinance 代號 → 中文名稱（上市為 .TW、上櫃為 .TWO）。
# 新增股票時加入任一組，下次執行即會自動補齊 HISTORY_START 起的歷史資料；同一檔可同時屬於多組。
# 回測除了個別股票與全部合併，也會依組別各自統計（例如比較「自選」與「0050 成分股」的結果是否一致）
STOCK_GROUPS: dict[str, dict] = {
    "TW50": {
        # 元大台灣50（0050）公布的持股，2026-10-30；0050 每季（3、6、9、12 月）調整成分股，需手動更新。
        # 注意：這是「現在」的成分股，回測期間（2015 年起）表現好的公司較可能留在名單中，回測結果會偏樂觀
        "name": "0050 成分股",
        "stocks": {
            "2330.TW": "台積電", "2454.TW": "聯發科", "2317.TW": "鴻海", "2308.TW": "台達電",
            "3711.TW": "日月光投控", "1303.TW": "南亞", "2383.TW": "台光電", "2881.TW": "富邦金",
            "2891.TW": "中信金", "3037.TW": "欣興", "2303.TW": "聯電", "2882.TW": "國泰金",
            "2887.TW": "台新新光金", "3017.TW": "奇鋐", "2382.TW": "廣達", "2885.TW": "元大金",
            "2345.TW": "智邦", "2327.TW": "國巨", "2884.TW": "玉山金", "2360.TW": "致茂",
            "2886.TW": "兆豐金", "2357.TW": "華碩", "2412.TW": "中華電", "2890.TW": "永豐金",
            "3443.TW": "創意", "2883.TW": "凱基金", "2408.TW": "南亞科", "6669.TW": "緯穎",
            "3231.TW": "緯創", "3653.TW": "健策", "2059.TW": "川湖", "2344.TW": "華邦電",
            "3008.TW": "大立光", "2892.TW": "第一金", "2301.TW": "光寶科", "2880.TW": "華南金",
            "1216.TW": "統一", "4958.TW": "臻鼎-KY", "6446.TW": "藥華藥", "2368.TW": "金像電",
            "2395.TW": "研華", "5880.TW": "合庫金", "8046.TW": "南電", "2449.TW": "京元電子",
            "2603.TW": "長榮", "3665.TW": "貿聯-KY", "7769.TW": "鴻勁", "4904.TW": "遠傳",
            "3045.TW": "台灣大", "6505.TW": "台塑化",
        },
    },
    "CUSTOM": {
        # 使用者自選（2026-10-30）
        "name": "自選",
        "stocks": {
            "0050.TW": "元大台灣50", "2303.TW": "聯電", "6770.TW": "力積電", "2408.TW": "南亞科",
            "2357.TW": "華碩", "1802.TW": "台玻", "1815.TWO": "富喬", "1303.TW": "南亞",
            "2308.TW": "台達電", "2344.TW": "華邦電", "2603.TW": "長榮", "2615.TW": "萬海",
            "2609.TW": "陽明", "3036.TW": "文曄", "2327.TW": "國巨", "4906.TW": "正文",
        },
    },
}

# 觀察名單：全部組別的聯集，yfinance 代號 → 中文名稱
WATCHLIST: dict[str, str] = {
    symbol: name for group in STOCK_GROUPS.values() for symbol, name in group["stocks"].items()
}


def groups_of(symbol: str) -> list[str]:
    """這檔股票所屬的組別代碼（依 STOCK_GROUPS 的順序）。"""
    return [code for code, group in STOCK_GROUPS.items() if symbol in group["stocks"]]

# 增量抓取：資料已存在自己的資料庫，每次只從資料庫最新的日期接著抓，並往前重疊這麼多個交易日：
# - 比對重疊日期的收盤價，偵測 Yahoo 是否因配股、減資、分割重新調整了歷史價格（見 fetcher.detect_readjustment）
# - 接收 Yahoo 對最近幾天的修正（例如成交量事後更正）
# 以交易日而非日曆天計算，連假（例如春節休市 9 天）也一定有重疊；電腦關機多久，下次執行就從那裡接著補
OVERLAP_TRADING_DAYS = 10

# 完整歷史的起始日（供回測使用）：台股漲跌幅自 2015-06-01 由 7% 放寬為 10%，從這天起市場規則一致。
# 資料庫尚未有這天起的資料（例如新加入觀察名單），或 Yahoo 因配股、減資、分割重新調整了歷史價格時，
# 會自動改抓這天起的完整歷史（見 main.py）
HISTORY_START = date(2015, 6, 1)

# JDBC 與 libpq（psycopg）的連線參數名稱不同，只轉換已知的參數
_QUERY_PARAM_MAP = {
    "sslmode": "sslmode",
    "channelBinding": "channel_binding",
    "channel_binding": "channel_binding",
}


class ConfigError(RuntimeError):
    """環境變數缺漏或格式錯誤。"""


def env_file_permission_warning() -> str | None:
    """.env 含資料庫密碼，若同一台電腦的其他帳號也能讀取，回傳提醒訊息。"""
    if ENV_FILE.exists() and ENV_FILE.stat().st_mode & 0o077:
        return f"{ENV_FILE} 其他帳號也能讀取（內含資料庫密碼），建議執行：chmod 600 {ENV_FILE}"
    return None


def build_database_url() -> URL:
    """由 DB_URL / DB_USERNAME / DB_PASSWORD 組出 SQLAlchemy 連線 URL。

    DB_URL 接受兩種格式（不可包含帳號密碼）：
      - JDBC：      jdbc:postgresql://host[:port]/dbname?sslmode=require
      - PostgreSQL：postgresql://host[:port]/dbname?sslmode=require
    帳密由 URL.create 另外帶入，密碼含 @ : / 等特殊字元也不需手動跳脫。
    """
    raw_url = os.getenv("DB_URL", "").strip()
    username = os.getenv("DB_USERNAME", "").strip()
    password = os.getenv("DB_PASSWORD", "")

    missing = [
        name
        for name, value in (("DB_URL", raw_url), ("DB_USERNAME", username), ("DB_PASSWORD", password))
        if not value
    ]
    if missing:
        raise ConfigError(f"缺少環境變數：{', '.join(missing)}（請檢查 stock-scraper-python/.env）")

    if raw_url.startswith("jdbc:"):
        raw_url = raw_url[len("jdbc:"):]

    parts = urlsplit(raw_url)
    if parts.scheme not in ("postgresql", "postgres"):
        raise ConfigError(f"DB_URL 開頭必須是 jdbc:postgresql:// 或 postgresql://，目前為 {parts.scheme!r}")
    if parts.username or parts.password:
        raise ConfigError("DB_URL 不可包含帳號密碼，請改用 DB_USERNAME / DB_PASSWORD")
    if not parts.hostname:
        raise ConfigError("DB_URL 缺少主機名稱")
    database = parts.path.lstrip("/")
    if not database:
        raise ConfigError("DB_URL 缺少資料庫名稱，例如 .../neondb")

    query = {}
    for key, value in parse_qsl(parts.query):
        if key in _QUERY_PARAM_MAP:
            query[_QUERY_PARAM_MAP[key]] = value
        else:
            logger.warning("忽略 DB_URL 中 Python 端不支援的參數：%s", key)

    return URL.create(
        drivername="postgresql+psycopg",
        username=username,
        password=password,
        host=parts.hostname,
        port=parts.port,
        database=database,
        query=query,
    )
