# Indexer

[Русский](README_ru.md) | English

Indexer is a console tool that walks a project directory, parses the sources into an AST and writes a structured index into a single SQLite file (`index.db`). An AI assistant can then query the index instead of reading the whole repository. There are two ways to use it:

- **Attach the file to a chat.** The assistant runs SQL on `index.db` (Python `sqlite3`, no internet), finds classes, methods, calls and dependencies, and reads source text by line range.
- **Publish it to an MCP server.** `mcp-sql-service` keeps the database on a server and exposes it over MCP (Streamable HTTP) to Perplexity or any other MCP client. After code changes you upload only a small patch.

What the index contains: files with text, Java types and members (signatures and line numbers), calls and type dependencies, imports and annotations, `module-info.java`, Gradle modules and dependencies, flat keys from yml/yaml/json/properties/toml, full-text search (FTS5) and git metadata.

The tool only reads the project. It does not run project code, does not start builds and does not use the network (except the explicit `push` command). The only external process is `git`, if it is installed.

## Requirements

| What | Version |
| --- | --- |
| JDK | 21 or newer |
| Gradle (to build) | 9.2 or newer (required by the Shadow plugin 9.6.1) |
| git | optional; with it the file list matches `.gitignore` exactly |

Platforms: build and run are verified on Windows. On Linux and macOS there are no native dependencies except `sqlite-jdbc` with bundled libraries, but the build has not been verified there yet.

## Build

The Gradle wrapper jar is not in the repository. Create it once with an installed Gradle, then:

```bash
gradlew clean test shadowJar        (Windows)
./gradlew clean test shadowJar      (Linux, macOS)
```

Result: `indexer-cli/build/libs/Indexer.jar`, a single fat jar (`Main-Class: ru.mcs.indexer.IndexerMain`). Copy it somewhere convenient, for example `D:\tools\Indexer.jar`.

## Quick start

From the root of the project you want to index:

```bash
java -jar D:\tools\Indexer.jar -l=java --stats
```

This creates `.index/index.db` and next to it `.index/QUERIES.md` with ready-made queries. Add `.index/` to the project's `.gitignore` (the tool never indexes the `.index/` directory itself).

Check the result:

```bash
python -c "import sqlite3; c=sqlite3.connect('.index/index.db'); print(c.execute('select key,value from meta').fetchall())"
```

## Command line

Both styles are supported: `-l=java` and `-l java`.

| Option | Default | Meaning |
| --- | --- | --- |
| `-r`, `--root` | current directory | project root |
| `-o`, `--out` | `<root>/.index/index.db` | database path |
| `-l`, `--lang` | `java` | comma-separated languages with AST parsing: `java` (implemented); `javascript`, `zig`, `kotlin` are not implemented yet and are ignored with a warning |
| `--include-ext` | empty | extra extensions stored as text and included in FTS (no leading dot) |
| `--exclude` | empty | extra gitignore-like exclusions, comma-separated |
| `--max-file-kb` | `500` | larger files are skipped (`SKIP_SIZE` record in `index_errors`) |
| `--incremental` | off | recompute only changed files (by SHA-256) |
| `--patch-dir` | none | with `--incremental`: also write a patch `patch-<from>-<to>.sql.gz` for the server into this directory |
| `--no-source` | off | do not store file text (`files.content = NULL`, text FTS is empty), the database is much smaller |
| `--no-resolve` | off | do not use SymbolSolver, faster, all calls get `resolved=0` |
| `--stats` | off | print table counters, errors by stage, time and size after indexing |
| `-v`, `--verbose` | off | verbose log |
| `-h`, `--help`, `-V`, `--version` | | help and version |

Subcommands: `push` (upload to the MCP server, see below), `diff` and `apply` (create and apply patches between two databases by hand).

Exit codes: `0` success (including non-fatal parse errors, they go to `index_errors`), `1` argument error, `2` fatal database or I/O error.

### System properties (diagnostics)

| Property | Meaning |
| --- | --- |
| `-Dindexer.threads=N` | number of worker threads (default 2 with resolve, up to 8 with `--no-resolve`) |
| `-Dindexer.resolve.budget.ms=N` | CPU budget for resolving one file, default 3000, `0` = no limit |
| `-Dindexer.resolve.filter=false` | disable the relevance filter (resolve all calls, slower) |
| `-Dindexer.profile=true` | with `-v` prints a thread profile |

In PowerShell put arguments with a dot in quotes: `java "-Dindexer.threads=1" -jar Indexer.jar ...`.

## Examples

```bash
:: index the current project, show the summary
java -jar Indexer.jar -l=java --stats

:: another directory, database in another place
java -jar Indexer.jar -r D:\work\myproj -o D:\work\myproj.index.db -l java

:: light version without file text (large projects, or publishing structure only)
java -jar Indexer.jar -l=java --no-source -o build\light.db --stats

:: fast, without resolving types and calls (about 9 s instead of 20+ s on a 500-file project)
java -jar Indexer.jar -l=java --no-resolve --stats

:: after edits: recompute only what changed
java -jar Indexer.jar -l=java --incremental --stats

:: extra extensions as text, exclude generated code and tests
java -jar Indexer.jar -l=java --include-ext=cfg,ini --exclude="/generated/,/src/test/"
```

## What goes into the index

Files are selected like this:

1. If the root has a git repository and `git` is available: `git ls-files -z --cached --others --exclude-standard`.
2. Otherwise the tool parses `.gitignore` itself (root and nested, `.git/info/exclude`): comments, `!` negation, `/` anchor, directories, `*`, `**`, `?`, `[abc]` classes.

Always excluded: `.git/`, `.gradle/`, `.idea/`, `.vscode/`, `node_modules/`, `.index/` at any depth, `build/`, `out/`, `target/` outside `src/` subtrees (so a package with such a name is not lost), `*.min.js`, `*.min.css`, binary files (NUL in the first 8 KB, `SKIP_BINARY`) and files larger than `--max-file-kb`.

Default extensions (stored in `files` and included in FTS): `java`, `gradle`, `kts`, `properties`, `yml`, `yaml`, `json`, `toml`, `css`, `md`, `sql`, `xml`, `txt`. AST is built only for languages from `--lang`. Structural parsing of Gradle and configs is always on.

Text is read as UTF-8; a non-UTF-8 file is read as windows-1251 and a `READ` warning is written to `index_errors`. Line endings are normalised to `\n`, BOM is removed. All line numbers in the tables refer to the normalised text in `files.content`.

Stages in `index_errors`: `READ`, `PARSE` (syntax and configs), `RESOLVE` (file resolve budget exhausted), `SKIP_SIZE`, `SKIP_BINARY`, `DB`. An error in one file does not stop indexing.

## Full and incremental modes

A full run (default) recreates the data and rebuilds FTS. `files.indexed_at` of files with an unchanged hash is preserved, so two runs in a row give identical tables (except `meta.indexed_at`).

`--incremental` reads and hashes all files, parses only new and changed ones, deletes vanished ones, and maintains FTS by hand in the same transaction. A no-op run on 502 files takes about 1.2 s and changes nothing. Gradle modules are recomputed every time from all scripts.

The incremental mode falls back to a full run (with a warning) if there is no previous index, the previous run did not finish, or the indexer version, `--lang`, `--no-source` or `--no-resolve` changed.

Limitation: files that depend on changed files are not recomputed, so `calls.callee_owner_fqn`, `resolved` and `type_deps` of those files may become stale. This is written to `meta.notes`; a full run is recommended after every 100 changed files (counter `meta.changes_since_full`).

The database is always a single file without `-wal` and `-shm`. `VACUUM` runs after a full run and after an incremental run that touched more than 20% of the files.

## Symbol resolution and speed

`SymbolSolver` uses the JDK and the project directories `src/main/java` and `src/test/java`. External JARs are not attached, so calls into libraries (JavaFX, JUnit and so on) keep `resolved=0`; `callee_name` and the call line are still stored. `resolved=1` means the solver confirmed the owner.

A relevance filter is on for speed: a call is resolved only if a method with that name is declared in the project. So JDK calls usually have `resolved=0` too; calls into project code are resolved fully.

Measured on a JavaFX project of 502 files (539 types, 23 thousand calls): full run 17–27 s, `--no-resolve` about 9–13 s, database 10.3 MB, 5.7 MB with `--no-source`.

## Schema overview

| Table | Content |
| --- | --- |
| `meta` | commit, branch, `git_dirty`, counters, `notes`, `indexed_at` |
| `files` | path, module, language, kind (`SOURCE_MAIN`, `SOURCE_TEST`, `RESOURCE`, `BUILD`, `DOC`, `OTHER`), text, SHA-256 |
| `types`, `type_relations` | classes, interfaces, enums, records, annotations; `extends`, `implements`, `permits` |
| `members` | methods, constructors, fields, enum constants, record components, initialisers |
| `calls`, `type_deps` | calls and object creation; "type depends on type" graph |
| `annotations`, `imports` | annotations with arguments; imports |
| `java_modules`, `module_directives` | `module-info.java` |
| `gradle_modules`, `gradle_deps` | Gradle modules and dependencies (`PROJECT` / `EXTERNAL`) |
| `config_entries` | flat keys such as `contributions.actions[0].id` |
| `code_fts` | FTS5 over `files` (path and text) |
| `index_errors` | everything that could not be processed |

The full description of relations and ready-made queries is in `QUERIES.md`.

## Query examples

Substitute your own names. Python (offline):

```python
import sqlite3
con = sqlite3.connect(".index/index.db")
```

Recommended order for an assistant: `meta` first, then type lookup, then members and calls, then text by line range.

Find a type and its members:

```sql
SELECT t.fqn, t.kind, f.path, t.line_from, t.line_to
FROM types t JOIN files f ON f.id = t.file_id
WHERE t.simple_name = 'IndexWriter';

SELECT m.kind, m.signature, m.line_from, m.line_to
FROM members m JOIN types t ON t.id = m.type_id
WHERE t.fqn = 'ru.mcs.indexer.db.IndexWriter'
ORDER BY m.line_from;
```

Who calls a method (with file, line and calling member):

```sql
SELECT f.path, c.line, c.resolved, ct.fqn AS caller_type, cm.signature AS caller_member
FROM calls c
JOIN files f ON f.id = c.file_id
LEFT JOIN members cm ON cm.id = c.caller_member_id
LEFT JOIN types ct ON ct.id = cm.type_id
WHERE c.callee_name = 'writeFile'
ORDER BY f.path, c.line;
```

Gradle module graph:

```sql
SELECT m.name, d.target FROM gradle_deps d JOIN gradle_modules m ON m.id = d.module_id
WHERE d.dep_kind = 'PROJECT' ORDER BY m.name, d.target;
```

Full-text search. Always wrap user text in double quotes (otherwise FTS5 treats dots, hyphens and colons as operators) and double any inner quotes:

```python
def fts_quote(text):
    return '"' + text.replace('"', '""') + '"'

rows = con.execute(
    "SELECT f.path, snippet(code_fts, 1, '[', ']', '…', 12) FROM code_fts "
    "JOIN files f ON f.id = code_fts.rowid WHERE code_fts MATCH ? LIMIT 20",
    (fts_quote("ru.mcs.Main"),)).fetchall()
```

Check that the database is not stale:

```python
import subprocess
indexed = dict(con.execute("SELECT key, value FROM meta")).get("git_commit", "unknown")
head = subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
if indexed != head:
    print(f"WARNING: index built at {indexed[:8]}, repository at {head[:8]}")
```

## Use with an AI assistant

### Option 1: attach the file

1. Build the index: `java -jar Indexer.jar -l=java`.
2. Attach `.index/index.db` and `.index/QUERIES.md` to the chat (use `--no-source` to publish the structure without code).
3. Ask the assistant to read `QUERIES.md` first, then follow the chain: `meta`, type, members and calls, text by line range.
4. After changes, refresh the index: `java -jar Indexer.jar -l=java --incremental`.

### Option 2: MCP server

`mcp-sql-service` is a separate Gradle module. It stores one SQLite database per workspace and serves it over MCP (Streamable HTTP, endpoint `/mcp`). It has two keys: a read key for MCP clients and a write key for uploading databases and patches.

MCP tools (all read-only):

| Tool | Purpose |
| --- | --- |
| `index_info` | Start here: projects, commit, branch, counters, staleness notes; `includeSchema=true` adds the table list |
| `find_type` | Find a type by simple name, fqn or suffix |
| `context` | One-call overview of a type: location, supertypes, subtypes, members, dependencies, dependents, resolved callers |
| `read_source` | Source of a type or of members by signature prefix |
| `read_lines` | Lines `from..to` of a file (up to 400 per call) |
| `search` | Full-text search over indexed text files |
| `callers` | Where a method is called |
| `sql` | One read-only SELECT/WITH on the index |

The service reads its settings from environment variables:

| Variable | Meaning |
| --- | --- |
| `MCPSQL_DATA_DIR` | directory where the service keeps databases (`/data` in Docker) |
| `MCPSQL_READ_KEY` | key for MCP clients (Bearer) |
| `MCPSQL_WRITE_KEY` | key for `push` (database and patches) |
| `MCPSQL_DOMAIN` | domain for Caddy and HTTPS (Docker Compose only) |

#### Deploy with Docker Compose

On the server create a directory with `docker-compose.yml`, `Caddyfile` and `.env`. Caddy obtains the certificate automatically; the service itself is not exposed, only ports 80 and 443 of Caddy.

```yaml
services:
  mcp-sql:
    image: malexple/mcp-sql:${MCPSQL_TAG:-0.1.0}
    restart: unless-stopped
    env_file: .env
    volumes:
      - mcp-data:/data
    expose:
      - "8080"

  caddy:
    image: caddy:2
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
    environment:
      - MCPSQL_DOMAIN=${MCPSQL_DOMAIN}
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy-data:/data
      - caddy-config:/config
    depends_on:
      - mcp-sql

volumes:
  mcp-data:
  caddy-data:
  caddy-config:
```

`Caddyfile`:

```
{$MCPSQL_DOMAIN} {
    reverse_proxy mcp-sql:8080
}
```

`.env` (generate the keys on the server and keep the file private with `chmod 600 .env`):

```
MCPSQL_DOMAIN=example.com
MCPSQL_DATA_DIR=/data
MCPSQL_READ_KEY=<random hex>
MCPSQL_WRITE_KEY=<random hex>
```

Before the first start, point the domain's DNS A record to the server and open TCP 80 and 443. Then:

```bash
docker compose pull
docker compose up -d
docker compose logs caddy
```

Do not run `docker compose down -v`: it deletes the volumes with the databases and the certificates.

#### Upload the index

```powershell
$env:MCPSQL_WRITE_KEY="<write key>"
java -jar Indexer.jar push --server https://example.com --workspace myproject --db .index\index.db
```

The server answers with `database uploaded: {... "seq":N ...}`.

#### Update with patches

Every indexer run increases `seq`. A patch only applies if it continues the server's `seq`, so after a full run always upload the whole database first.

```powershell
java -jar Indexer.jar -l=java --incremental --patch-dir .index\patches --stats
java -jar Indexer.jar push --server https://example.com --workspace myproject --patch-dir .index\patches
```

Expected: `patch: ...patch-5-6.sql.gz (seq 5 -> 6, ...)`, then `applied patch-5-6.sql.gz` and `server is now at seq 6`. If the sequence diverged (`server is at seq N; no patch ... continues it`), upload the database again with `push --db`.

#### Connect to Perplexity

1. Open **Plugins → Add custom → Add MCP connector → Remote** (custom connectors need a Pro, Max or Enterprise plan).
2. Name `Indexer`, MCP server URL `https://example.com/mcp`.
3. In **Advanced** choose Authentication `API Key` with the **read** key, Transport `Streamable HTTP`, Network access `Public`.
4. Tick the risk acknowledgement, click **Add**, then click the connector card to enable it.
5. In a new thread enable the connector and ask, for example, "show index_info" or "find the type IndexWriter".

Use the read key only. The write key must never be given to an MCP client.

## Troubleshooting

| Symptom | What to do |
| --- | --- |
| `Could not find or load main class .threads=1` (PowerShell) | put `-D` in quotes: `java "-Dindexer.threads=1" -jar ...` |
| `No suitable driver found` | rebuild with `shadowJar`; `mergeServiceFiles()` is required in the plugin configuration (already enabled) |
| `not an SQLite database, refusing to overwrite` | a foreign file lies at the `-o` path; the tool overwrites nothing, choose another path |
| `incremental run is not possible (...)` | a warning: options or version changed, a full run was done |
| Many `resolved=0` | expected for JDK and library calls; for project code check that sources are in `src/main/java` or `src/test/java` |
| `RESOLVE` in `index_errors` | the file resolve budget (3 s CPU) is exhausted; raise `-Dindexer.resolve.budget.ms` or accept `resolved=0` |
| Run takes longer than 30 s | `-Dindexer.resolve.budget.ms=1500`, then `--no-resolve`; more threads do not help: each thread keeps its own solver caches |
| `SKIP_SIZE` | the file is larger than `--max-file-kb`; raise the limit if you need it |
| `patch: no data changes, no patch written` | file hashes match the database; edit a file and run again |
| `server is at seq N; no patch ... continues it` | upload the whole database with `push --db` and delete old patches |
| New files are not indexed | the list comes from `git ls-files`; add the file to git (`git add`) |
| MCP client gets 401 | check the read key and the `Authorization: Bearer` header; see `docker compose logs mcp-sql` |

## Development

Tests (JUnit 5, AssertJ, `@TempDir`): `gradlew test`. They cover Java parsing (types, members, signatures, line numbers), calls and resolve, `module-info`, Gradle, configs, `.gitignore`, size limits and binary files, syntax errors, incremental mode, FTS consistency (`integrity-check` with `rank=1`), idempotence, absence of `-wal` and `-shm`, `foreign_key_check` and execution of all SQL blocks of `QUERIES.md`.

Structure: `ru.mcs.indexer` (`IndexerMain`, `IndexerApp`), `cli` (picocli), `files` (file selection, `.gitignore`, git), `db` (SQLite, transactions, writing), `pipeline` (worker pool and a single writer), `lang` (registry and indexers: `java`, `gradle`, `config`).

A new language: a class implementing `LanguageIndexer` (the `index(FileContext, FileFacts)` method only collects rows into a buffer and must be thread-safe) and one registration line in `LanguageRegistry`. No other code needs to change.

## Limitations

- `this(...)` and `super(...)` calls are not indexed.
- Gradle dependencies written on several lines and `libs.*` from a version catalog are not recognised.
- Resolve does not see external JARs (by project design).
- Inline TOML tables and arrays are stored as the raw value text.
- The languages `javascript`, `zig` and `kotlin` are not implemented yet.
- The MCP service is read-only for clients: it cannot modify your code.

## License

The source code is distributed under the MIT license, see `LICENSE`. Licenses of the bundled libraries are listed in `THIRD-PARTY-NOTICES.md`.
