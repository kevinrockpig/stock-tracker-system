"""環境變數讀取、資料庫連線 URL 組裝與觀察名單設定。"""

import logging
import os
from pathlib import Path
from urllib.parse import parse_qsl, urlsplit

from dotenv import load_dotenv
from sqlalchemy.engine import URL

logger = logging.getLogger(__name__)

PROJECT_ROOT = Path(__file__).resolve().parent.parent
ENV_FILE = PROJECT_ROOT / ".env"

# 載入 stock-scraper-python/.env；系統中已存在的環境變數優先，不會被 .env 覆蓋
load_dotenv(ENV_FILE)

# 觀察名單：yfinance 代號 → 中文名稱（新增股票時在此加入，下次執行即會擷取）
WATCHLIST: dict[str, str] = {
    "2330.TW": "台積電",
    "2317.TW": "鴻海",
    "2454.TW": "聯發科",
    "0050.TW": "元大台灣50",
}

# 每次重抓兩年：前一年作為 MACD / RSI / KD 的暖身資料（見 indicators.WARMUP_BARS），
# 讓圖表顯示的最近一年指標與券商軟體一致；也會自動補上先前 yfinance 漏掉的交易日
# 數值沒變的資料不會被更新，重複抓取的成本很低
DEFAULT_PERIOD = "2y"

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
