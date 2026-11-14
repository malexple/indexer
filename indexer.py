import sqlite3
c = sqlite3.connect(".index/index.db")
print(c.execute("select resolved, count(*) from calls group by resolved").fetchall())
print(c.execute("""select c.callee_name, c.callee_owner_fqn, m.line_from, f.path
  from calls c join types t on t.fqn=c.callee_owner_fqn
  join members m on m.type_id=t.id and m.signature=c.callee_signature
  join files f on f.id=m.file_id where c.callee_name='writeFile' limit 5""").fetchall())
print(c.execute("select dep_kind, count(*) from type_deps group by dep_kind").fetchall())