"""官方開放資料的 CSV 備份：每個來源、每天一個 gzip 壓縮的 CSV 檔，保留官方原始欄位。

開放資料只提供最近一日、無法補回，資料庫之外另留一份備份，資料庫出問題時可由此重新匯入。

- 存放位置：環境變數 ARCHIVE_DIR；未設定時為 stock-scraper-python/archive/（已被 .gitignore 排除）。
  GitHub Actions 會把 ARCHIVE_DIR 指向私人備份 repo，再 commit 進去
- 檔案路徑：<ARCHIVE_DIR>/<來源>/<年>/<日期>.csv.gz，例如 twse_csv/2026/2026-10-29.csv.gz
- 內容相同時不改寫檔案（gzip 不寫入時間戳記），重複執行不會讓 Git 產生多餘的變更
"""

import csv
import gzip
import io
import os
from datetime import date
from pathlib import Path

from scraper.config import PROJECT_ROOT


def archive_dir() -> Path:
    return Path(os.getenv("ARCHIVE_DIR") or PROJECT_ROOT / "archive")


def archive_path(base: Path, source_key: str, day: date) -> Path:
    return base / source_key / f"{day:%Y}" / f"{day:%Y-%m-%d}.csv.gz"


def to_csv_gz(records: list[dict]) -> bytes:
    """轉為 gzip 壓縮的 CSV；欄位順序依原始資料出現順序。mtime=0 讓相同內容產生相同位元組。"""
    fieldnames = list(dict.fromkeys(key for record in records for key in record))
    buffer = io.StringIO()
    writer = csv.DictWriter(buffer, fieldnames=fieldnames, lineterminator="\n")
    writer.writeheader()
    writer.writerows(records)
    return gzip.compress(buffer.getvalue().encode("utf-8"), mtime=0)


def write_archive(base: Path, source_key: str, day: date, records: list[dict]) -> bool:
    """寫入備份檔，回傳是否有新增或變更（內容相同則不寫）。先寫暫存檔再改名，避免中斷時留下不完整的檔案。"""
    path = archive_path(base, source_key, day)
    data = to_csv_gz(records)
    if path.exists() and path.read_bytes() == data:
        return False
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_bytes(data)
    temporary.replace(path)
    return True
