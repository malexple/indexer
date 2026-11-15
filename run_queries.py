import re
import sqlite3
import sys
from pathlib import Path

db = Path(sys.argv[1] if len(sys.argv) > 1 else ".index/index.db")
doc = (db.parent / "QUERIES.md").read_text(encoding="utf-8")
con = sqlite3.connect(db)
blocks = re.findall(r"```sql\n(.*?)```", doc, re.S)
failed = 0
for number, block in enumerate(blocks, 1):
    sql = block.strip()
    first = sql.splitlines()[0][:70]
    try:
        rows = con.execute(sql).fetchall()
        print(f"#{number:02d} OK   rows={len(rows):5d}  {first}")
    except Exception as exc:
        failed += 1
        print(f"#{number:02d} FAIL {exc}\n{sql}")
print(f"{len(blocks)} blocks, {failed} failed")
sys.exit(1 if failed else 0)