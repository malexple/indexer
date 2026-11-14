import sqlite3
c = sqlite3.connect(".index/index.db")
print(c.execute("select key, value from meta").fetchall())
print(c.execute("select count(*) from files").fetchone())