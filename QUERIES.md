# Querying index.db

`index.db` is a single SQLite file produced by the Indexer. Open it with Python's `sqlite3` (no network needed).
Line numbers everywhere refer to the normalized text stored in `files.content` (`\n` line endings, BOM removed).

## Rules for the assistant

Work in this order: **first `meta`, then find the type, then its members and calls, then read the text by line range.**

1. Read `meta` (query 1). If `source_included = '0'`, `files.content` is NULL: you can navigate but not read source.
2. Locate the type (query 2), then list its members (query 3). Use `calls` and `type_deps` to follow relations (queries 4-8).
3. To read code, take `files.content` and cut `line_from..line_to` (query 13). Method text is never duplicated in the database.
4. Always put user-supplied FTS5 text into double quotes and double any inner quote (query 14).
5. `resolved = 1` means the symbol solver confirmed the owner; `resolved = 0` means a best-effort name only
   (calls into the JDK and libraries are intentionally left unresolved). Check `meta.notes` for staleness warnings.

## Schema in short

- `meta(key, value)`: indexing facts (commit, branch, counts, notes).
- `files(id, path, module, lang, kind, size_bytes, line_count, sha256, content, indexed_at)`.
- `types(id, file_id, parent_type_id, fqn, simple_name, package, kind, modifiers, is_public, line_from, line_to, doc_summary)`.
- `type_relations(type_id -> types, relation EXTENDS|IMPLEMENTS|PERMITS, target_name, target_fqn, resolved)`.
- `members(id, type_id -> types, file_id -> files, kind, name, signature, return_type, modifiers, line_from, line_to, doc_summary)`;
  `signature` looks like `open(ru.mcs.spi.FileEditor,boolean):void` (constructors `Name(args)`, fields `name:type`).
- `annotations(file_id, owner_kind TYPE|MEMBER, owner_id -> types.id or members.id, name, args, line)`.
- `imports(file_id, name, is_static, is_asterisk, line)`.
- `calls(file_id, caller_member_id -> members, call_kind, callee_name, callee_owner_fqn, callee_signature, resolved, line)`;
  `callee_owner_fqn + callee_signature` joins to `types.fqn + members.signature`.
- `type_deps(from_type_id -> types, to_fqn, dep_kind, resolved)`, kinds: IMPORT EXTENDS IMPLEMENTS FIELD PARAM RETURN NEW CALL ANNOTATION.
- `gradle_modules(name, dir, build_file)`, `gradle_deps(module_id -> gradle_modules, config, dep_kind PROJECT|EXTERNAL, target, line)`.
- `java_modules(file_id, name, is_open)`, `module_directives(java_module_id, directive, target, extra, line)`.
- `config_entries(file_id, key_path, value, line)`: flattened yml/yaml/json/properties/toml.
- `index_errors(file_id?, path, stage, message)`: stages READ PARSE RESOLVE SKIP_SIZE SKIP_BINARY DB.
- `code_fts(path, content)`: FTS5 index over `files` (external content, rowid = `files.id`).

Examples below use names from the Indexer project itself; replace the literals with your own.

## 1. Metadata

```sql
SELECT key, value FROM meta ORDER BY key;
```

## 2. Find a type by simple name or FQN

```sql
SELECT t.fqn, t.kind, t.modifiers, f.path, t.line_from, t.line_to, t.doc_summary
FROM types t JOIN files f ON f.id = t.file_id
WHERE t.simple_name = 'IndexWriter'
ORDER BY t.fqn;
```

```sql
SELECT t.fqn, t.kind, f.path, t.line_from, t.line_to
FROM types t JOIN files f ON f.id = t.file_id
WHERE t.fqn LIKE '%.IndexWriter' OR t.fqn LIKE 'ru.mcs.indexer.db.%'
ORDER BY t.fqn LIMIT 50;
```

## 3. Members of a type with signatures

```sql
SELECT m.kind, m.signature, m.modifiers, m.line_from, m.line_to, m.doc_summary
FROM members m JOIN types t ON t.id = m.type_id
WHERE t.fqn = 'ru.mcs.indexer.db.IndexWriter'
ORDER BY m.line_from;
```

## 4. Who implements interface X / who extends class Y

```sql
SELECT t.fqn, t.kind, f.path, t.line_from
FROM type_relations r JOIN types t ON t.id = r.type_id JOIN files f ON f.id = t.file_id
WHERE r.relation = 'IMPLEMENTS'
  AND (r.target_fqn = 'ru.mcs.indexer.lang.LanguageIndexer' OR r.target_name = 'LanguageIndexer')
ORDER BY t.fqn;
```

```sql
SELECT t.fqn, t.kind, f.path, t.line_from
FROM type_relations r JOIN types t ON t.id = r.type_id JOIN files f ON f.id = t.file_id
WHERE r.relation = 'EXTENDS'
  AND (r.target_fqn = 'java.lang.Object' OR r.target_name = 'Base')
ORDER BY t.fqn;
```

## 5. Who calls a method

By name, with file, line and the calling member:

```sql
SELECT f.path, c.line, c.call_kind, c.callee_owner_fqn, c.callee_signature, c.resolved,
       ct.fqn AS caller_type, cm.signature AS caller_member
FROM calls c
JOIN files f ON f.id = c.file_id
LEFT JOIN members cm ON cm.id = c.caller_member_id
LEFT JOIN types ct ON ct.id = cm.type_id
WHERE c.callee_name = 'writeFile'
ORDER BY f.path, c.line;
```

A method of a specific owner (precise, only resolved calls match):

```sql
SELECT f.path, c.line, c.callee_signature
FROM calls c JOIN files f ON f.id = c.file_id
WHERE c.callee_owner_fqn = 'ru.mcs.indexer.db.IndexWriter' AND c.callee_name = 'writeFile'
ORDER BY f.path, c.line;
```

Calls that could not be resolved (library/JDK calls, or symbols the solver missed):

```sql
SELECT c.callee_name, COUNT(*) AS n
FROM calls c WHERE c.resolved = 0
GROUP BY c.callee_name ORDER BY n DESC LIMIT 30;
```

## 6. What does a method call

```sql
SELECT c.line, c.call_kind, c.callee_name, c.callee_owner_fqn, c.callee_signature, c.resolved
FROM calls c
WHERE c.caller_member_id = (
    SELECT m.id FROM members m JOIN types t ON t.id = m.type_id
    WHERE t.fqn = 'ru.mcs.indexer.db.IndexWriter' AND m.signature LIKE 'writeFile(%'
    ORDER BY m.line_from LIMIT 1)
ORDER BY c.line;
```

## 7. Type dependencies: what X uses and who uses X

```sql
SELECT d.dep_kind, d.to_fqn, d.resolved
FROM type_deps d JOIN types t ON t.id = d.from_type_id
WHERE t.fqn = 'ru.mcs.indexer.db.IndexWriter'
ORDER BY d.dep_kind, d.to_fqn;
```

```sql
SELECT t.fqn, d.dep_kind, f.path
FROM type_deps d JOIN types t ON t.id = d.from_type_id JOIN files f ON f.id = t.file_id
WHERE d.to_fqn = 'ru.mcs.indexer.db.Db'
ORDER BY t.fqn, d.dep_kind;
```

## 8. Reverse dependencies of a package or module

Which classes of module `core` are used by the plugins:

```sql
SELECT d.to_fqn, COUNT(DISTINCT t.id) AS users, GROUP_CONCAT(DISTINCT f.module) AS from_modules
FROM type_deps d
JOIN types t ON t.id = d.from_type_id
JOIN files f ON f.id = t.file_id
WHERE f.module LIKE 'plugins/%'
  AND d.to_fqn IN (SELECT ty.fqn FROM types ty JOIN files tf ON tf.id = ty.file_id WHERE tf.module = 'core')
GROUP BY d.to_fqn
ORDER BY users DESC LIMIT 50;
```

## 9. module-info.java: provides and uses

```sql
SELECT m.name AS module, d.directive, d.target, d.extra, d.line
FROM module_directives d JOIN java_modules m ON m.id = d.java_module_id
WHERE d.directive IN ('provides', 'uses')
ORDER BY m.name, d.directive, d.target;
```

## 10. Gradle modules and dependencies

```sql
SELECT m.name AS module, d.config, d.dep_kind, d.target, d.line
FROM gradle_deps d JOIN gradle_modules m ON m.id = d.module_id
WHERE m.name = 'core'
ORDER BY d.dep_kind, d.config, d.target;
```

Module graph (PROJECT dependencies only):

```sql
SELECT m.name AS module, d.target AS depends_on
FROM gradle_deps d JOIN gradle_modules m ON m.id = d.module_id
WHERE d.dep_kind = 'PROJECT'
ORDER BY m.name, d.target;
```

## 11. Files of a module, largest files, files with errors

```sql
SELECT path, kind, lang, line_count, size_bytes FROM files WHERE module = 'core' ORDER BY path;
```

```sql
SELECT path, size_bytes, line_count FROM files ORDER BY size_bytes DESC LIMIT 20;
```

```sql
SELECT e.stage, e.path, e.message FROM index_errors e ORDER BY e.stage, e.path;
```

## 12. Plugin contributions and message keys

```sql
SELECT f.path, c.key_path, c.value, c.line
FROM config_entries c JOIN files f ON f.id = c.file_id
WHERE f.path LIKE '%plugin.yml' AND c.key_path LIKE 'contributions.%'
ORDER BY f.path, c.line;
```

```sql
SELECT f.path, c.key_path, c.value
FROM config_entries c JOIN files f ON f.id = c.file_id
WHERE f.path LIKE '%messages%.properties' AND c.key_path LIKE 'action.%'
ORDER BY f.path, c.key_path;
```

## 13. Read the source of a method or a type

The SQL returns the whole file text plus the range; cut the lines on the client:

```sql
SELECT f.path, f.content, m.line_from, m.line_to
FROM members m JOIN types t ON t.id = m.type_id JOIN files f ON f.id = m.file_id
WHERE t.fqn = 'ru.mcs.indexer.db.IndexWriter' AND m.signature LIKE 'writeFile(%'
ORDER BY m.line_from LIMIT 1;
```

```sql
SELECT f.path, f.content, t.line_from, t.line_to
FROM types t JOIN files f ON f.id = t.file_id
WHERE t.fqn = 'ru.mcs.indexer.db.IndexWriter';
```

```python
import sqlite3

con = sqlite3.connect("index.db")
path, content, line_from, line_to = con.execute(
    "SELECT f.path, f.content, m.line_from, m.line_to FROM members m "
    "JOIN types t ON t.id = m.type_id JOIN files f ON f.id = m.file_id "
    "WHERE t.fqn = ? AND m.signature LIKE ? ORDER BY m.line_from LIMIT 1",
    ("ru.mcs.indexer.db.IndexWriter", "writeFile(%")).fetchone()
lines = content.split("\n")
print(f"// {path}:{line_from}-{line_to}")
print("\n".join(lines[line_from - 1:line_to]))
```

## 14. Full-text search

Wrap every user token in double quotes (FTS5 treats dots, hyphens and colons as operators otherwise);
for a phrase use `'"foo bar"'`; double inner quotes.

```sql
SELECT f.path, snippet(code_fts, 1, '[', ']', '…', 12)
FROM code_fts JOIN files f ON f.id = code_fts.rowid
WHERE code_fts MATCH '"ru.mcs.Main"'
ORDER BY f.path LIMIT 20;
```

Search file paths only:

```sql
SELECT f.path FROM code_fts JOIN files f ON f.id = code_fts.rowid
WHERE code_fts MATCH 'path : "IndexWriter"'
ORDER BY f.path LIMIT 20;
```

```python
def fts_quote(text):
    return '"' + text.replace('"', '""') + '"'

rows = con.execute(
    "SELECT f.path, snippet(code_fts, 1, '[', ']', '…', 12) FROM code_fts "
    "JOIN files f ON f.id = code_fts.rowid WHERE code_fts MATCH ? LIMIT 20",
    (fts_quote("ru.mcs.Main"),)).fetchall()
```

## 15. Is the index stale?

```sql
SELECT key, value FROM meta
WHERE key IN ('git_commit', 'git_commit_short', 'git_branch', 'git_dirty', 'indexed_at', 'changes_since_full', 'notes');
```

```python
import subprocess

indexed = dict(con.execute("SELECT key, value FROM meta")).get("git_commit", "unknown")
try:
    head = subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
except Exception:
    head = "unknown"
if indexed != head:
    print(f"WARNING: index was built at {indexed[:8]}, repository is at {head[:8]}; results may be stale")
```