# Indexer

[Русский](README_ru.md) | English

Indexer is a console tool that walks a project directory, parses the sources into an AST and writes a structured index into a single SQLite file (`index.db`). An AI assistant can then query the index instead of reading the whole repository. There are two ways to use it:

- **Attach the file to a chat.** The assistant runs SQL on `index.db` (Python `sqlite3`, no internet), finds classes, methods, calls and dependencies, and reads source text by line range.
- **Publish it to an MCP server.** `mcp-sql-service` keeps the database on a server and exposes it over MCP (Streamable HTTP) to Perplexity or any other MCP client. After code changes you upload only a small patch.
- **Host it for many users.** The same service can run in multi-user mode: a Telegram bot (`@mcp_sql_bot`) issues short-lived key pairs, every user gets isolated databases with limits, and a small website shows the public statistics.

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

#### Addresses

| Address | What it serves |
| --- | --- |
| `/mcp` | All workspaces of the key's owner; pass the `workspace` argument when there are several |
| `/mcp/{workspace}` | One workspace; the server fills in the `workspace` argument |
| `/mcp/{workspace}/{project}` | One project inside a workspace; the server fills in `workspace` and `project` |

On the two bound addresses the values from the path win: a tool call that names another workspace or project is refused. An unknown workspace answers `404 not found`.

REST API (write key): `GET /api/workspaces`, `GET /api/workspaces/{workspace}`, `PUT /api/workspaces/{workspace}/db`, `POST /api/workspaces/{workspace}/patch`, `DELETE /api/workspaces/{workspace}` and, in multi-user mode, `GET /api/usage` (today's counters and limits). `GET /health` needs no key.

#### Single-user and multi-user mode

By default (`MCPSQL_MULTI_ENABLED=false`) the service has one owner and two static keys from the environment (`MCPSQL_READ_KEY`, `MCPSQL_WRITE_KEY`); the databases lie directly in `MCPSQL_DATA_DIR`. This is the mode for your own machine or a private server.

With `MCPSQL_MULTI_ENABLED=true` the static keys are ignored. Keys are issued per user by a Telegram bot, every user has a private directory `users/{id}/` inside the data directory, and the limits below apply.

#### Hosted multi-user service

How a user works with it:

1. Open the bot (`@mcp_sql_bot`), press **Get keys** and choose a lifetime: 5 hours, 1 day or 7 days. The bot sends two keys. The write key (`mcp_w_...`) is yours: upload, patches, delete; it can read as well. The read key (`mcp_r_...`) is for the AI assistant and can only read. Both are shown once; the server stores only their SHA-256 hashes.
2. Upload the index with the write key (`push`, see below).
3. Give the AI assistant the read key and the address `https://<domain>/mcp/<workspace>`.

The bot menu also has **My data** (key expiry, databases, today's usage and limits), **Statistics**, **Help** and **Delete everything**. The last one revokes the keys and deletes the user's databases at once. It is always available, also when the daily key limit is used up, and it gives the day's key issues back, so a user whose keys leaked can always start again.

Lifetime of data: a new pair of keys replaces the previous one and restarts the lifetime of the user's databases. When the keys expire without renewal, the databases are deleted automatically (within about a minute) and then the expired key records. The user record (Telegram id) and the daily counters stay.

Limits (days are UTC days; rejected uploads count too):

| Limit | Default | Variable |
| --- | --- | --- |
| Users with live keys at the same time | 50 | `MCPSQL_MAX_ACTIVE_USERS` |
| Key issues per user per day | 5 | `MCPSQL_KEY_ISSUES_PER_DAY` |
| Workspaces per user | 3 | `MCPSQL_MAX_WORKSPACES` |
| Size of one database (also after a patch) | 20 MB | `MCPSQL_MAX_DB_MB` |
| Database uploads per day | 10 | `MCPSQL_UPLOADS_PER_DAY` |
| Uploaded volume per day (databases and patches together) | 300 MB | `MCPSQL_WRITE_MB_PER_DAY` |
| Pause between patches of one user | 30 s | `MCPSQL_PATCH_INTERVAL_SECONDS` |
| MCP requests (POST to `/mcp`) per day | 5000 | `MCPSQL_REQUESTS_PER_DAY` |
| Free disk space below which uploads are refused | 2048 MB | `MCPSQL_MIN_FREE_DISK_MB` |

Refusals: `401` (no, wrong, expired or revoked key, or a read key where a write key is needed), `404` (unknown workspace; the same answer for a missing and for a foreign one), `409` (workspace limit), `429` with a JSON `code` (`KEY_ISSUE_LIMIT`, `UPLOAD_LIMIT`, `WRITE_BYTES_LIMIT`, `PATCH_INTERVAL`, `REQUEST_LIMIT`), `503` (the server is full when keys are issued), `507` (low disk space).

The bot and the website are separate modules (see "Repository modules"). The bot keeps no keys and talks to the service through `/internal/*`, which needs the `X-Internal-Key` header and must never be published. The website shows aggregated statistics only: users, key pairs by lifetime, used and free disk space and the limits.

#### Deploy on a server

Four containers run on the server: `mcp-sql` (service), `mcp-sql-bot`, `mcp-sql-web` and `caddy` (HTTPS and routing). Only ports 80 and 443 of Caddy are open. The files are in `mcp-sql-service/`: `docker-compose.yml`, `Caddyfile` and `.env.example`; the step-by-step guide is in [`mcp-sql-service/DEPLOY.md`](mcp-sql-service/DEPLOY.md) (in Russian).

`Caddyfile` (the service gets MCP and the user API, the website gets the rest, `/internal` is not published):

```
{$MCPSQL_DOMAIN} {
    @service path /mcp /mcp/* /api/workspaces /api/workspaces/* /api/usage
    handle @service {
        request_body {
            max_size 25MB
        }
        reverse_proxy mcp-sql:8080 {
            flush_interval -1
        }
    }
    handle {
        encode zstd gzip
        reverse_proxy mcp-sql-web:8080
    }
}
```

The main `.env` settings (everything else is in `.env.example`):

| Variable | Meaning |
| --- | --- |
| `MCPSQL_DOMAIN` | domain for Caddy and HTTPS |
| `MCPSQL_TAG` | image version (`malexple/mcp-sql`, `malexple/mcp-sql-bot`, `malexple/mcp-sql-web`) |
| `MCPSQL_MULTI_ENABLED` | `true` for the multi-user mode |
| `MCPSQL_INTERNAL_KEY` | secret between the bot and the service (`openssl rand -hex 32`) |
| `BOT_TOKEN` | token from BotFather |
| `BOT_PUBLIC_URL` | address that the bot shows to users |

Before the first start point the domain's DNS A record to the server and open TCP 80 and 443. Then `docker compose pull`, `docker compose up -d` and `docker compose logs`. Do not run `docker compose down -v`: it deletes the volumes with the databases and the certificates. Check `https://<domain>/internal/stats`: it must answer 404.

Release: `.\scripts\publish.ps1 -Version 0.1.1` (Linux and macOS: `scripts/publish.sh 0.1.1`) runs the tests, builds the three images for linux/amd64 and pushes them; `-NoPush` only builds. Then set `MCPSQL_TAG` on the server and run `docker compose pull` and `docker compose up -d`. To roll back, set the previous tag.


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

In hosted mode use the read key from the bot and the address `https://<domain>/mcp/<workspace>` instead of `/mcp`.


## Local check

This section checks the chain Indexer -> mcp-sql-service -> MCP queries on your own computer. The examples come from a real check of a project with 586 files, 710 types and 5378 members. Assumed paths: repository `D:\project\java\indexer`, jar `D:\project\Indexer.jar`, project `D:\project\java\manuscriptium`, server `http://localhost:8080`, keys `read-secret` and `write-secret` (for local use only), workspace `manuscriptium`.

### 1. Build Indexer.jar

```powershell
cd D:\project\java\indexer
./gradlew clean build
```

Copy the jar from `indexer-cli\build\libs\` to `D:\project\Indexer.jar`. `java -jar D:\project\Indexer.jar --help` must list `apply`, `diff` and `push`.

### 2. Start the service

Docker: create `.env` next to `docker-compose.yml` (`MCPSQL_DOMAIN=localhost`, `MCPSQL_DATA_DIR=/data`, `MCPSQL_READ_KEY=read-secret`, `MCPSQL_WRITE_KEY=write-secret`) and run

```powershell
cd D:\project\java\indexer\mcp-sql-service
./gradlew :mcp-sql-service:bootJar
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d --build mcp-sql
docker compose logs mcp-sql
```

The local file builds the image from the sources and publishes port 8080 on `127.0.0.1` only. A new build is needed after every code change: tests do not update a running container. If Docker Desktop reports `request returned Internal Server Error for API route`, restart Docker Desktop, run `wsl --update` and `wsl --shutdown`, then start it again.

Without Docker: `./gradlew :mcp-sql-service:bootJar`, set `MCPSQL_DATA_DIR`, `MCPSQL_READ_KEY` and `MCPSQL_WRITE_KEY` in the environment and run `java -jar mcp-sql-service\build\libs\app.jar` in a separate window.

### 3. First index and upload

From the project directory:

```powershell
java -jar ..\..\Indexer.jar -r . -l=java --stats
$env:MCPSQL_WRITE_KEY="write-secret"
java -jar ..\..\Indexer.jar push --server http://localhost:8080 --workspace manuscriptium --db .index\index.db
```

Expect `mode=full`, `failed=0`, `seq=1` and then `database uploaded: {"workspace":"manuscriptium","dbId":"...","seq":1,...}`.

### 4. MCP queries

Every request is `POST http://localhost:8080/mcp` with the headers `Authorization: Bearer read-secret`, `Content-Type: application/json` and `Accept: application/json, text/event-stream`. The answer is in `result.content[0].text`.

```bash
curl -X POST 'http://localhost:8080/mcp' \
  -H 'Authorization: Bearer read-secret' \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  --data-raw '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"index_info","arguments":{"workspace":"manuscriptium"}}}'
```

Replace the method with `tools/list` to list the tools. Typical chain: `index_info`, then `find_type` (argument `name`), `context` (`fqn`), `read_source` (`fqn`, `member`), `search` (`text`), `callers` (`name`), `read_lines` (`path`, `from`, `to`), and `sql` for anything else, for example `SELECT name, dir FROM gradle_modules ORDER BY name` (8 rows on this project). The `workspace` argument is optional while the server holds one workspace; `project` is optional, without it the search covers all projects.

### 5. Changes: incremental run and patch

The order is strict: a patch applies only if it continues the server's `seq`.

1. After every full run upload the database: `push --db`.
2. Change the project files.
3. Run `java -jar ..\..\Indexer.jar -r . -l=java --incremental --patch-dir .index\patches --stats`. Expect `mode=incremental` and a line like `patch: ...patch-5-6.sql.gz (seq 5 -> 6, ...)`.
4. Run `java -jar ..\..\Indexer.jar push --server http://localhost:8080 --workspace manuscriptium --patch-dir .index\patches`. Expect `applied patch-5-6.sql.gz` and `server is now at seq 6`.
5. Check with `index_info` (new `seq`, field `notes`) and with `search` for the text you changed.

### 6. Common situations

| Message or symptom | Cause | What to do |
| --- | --- | --- |
| `patch: no data changes, no patch written` | file hashes match the database | normal; change a file again |
| no patch after a full run | a full run (no `--incremental`) writes no patch | `push --db`, then edit and run `--incremental --patch-dir` |
| `server is at seq N; no patch ... continues it` | the local `seq` went ahead of the server, the chain is broken | upload the whole database with `push --db` and delete old patches |
| `seq` grew by several units | every indexer run increases `seq`, repeated full runs too | avoid extra runs between `push --db` and a patch |
| new files are not visible | the file list comes from `git ls-files` | `git add` the file |
| `notes` says "full run recommended" | after about 100 changes dependent files may have stale `resolved` and `type_deps` | one full run and `push --db` |

### 7. Multi-user mode locally

1. Add to `.env`: `MCPSQL_MULTI_ENABLED=true`, `MCPSQL_INTERNAL_KEY=internal-secret`, `BOT_TOKEN=<token>` (needed only for the bot) and `BOT_PUBLIC_URL=http://localhost:8080`.
2. Build all jars and start everything: `./gradlew :mcp-sql-service:bootJar :mcp-sql-bot:bootJar :mcp-sql-web:bootJar`, then `docker compose -f docker-compose.yml -f docker-compose.local.yml up -d --build`. The website is at `http://127.0.0.1:8081`.
3. Get keys without the bot:

```bash
curl -X POST http://localhost:8080/internal/keys \
  -H 'Content-Type: application/json' -H 'X-Internal-Key: internal-secret' \
  --data '{"tgId": 1001, "ttl": "1d"}'
```

4. Upload with the returned `writeKey` (`MCPSQL_WRITE_KEY`) and query `http://localhost:8080/mcp/manuscriptium` with the `readKey`. A second user (another `tgId`) gets `404 not found` for the same address.
5. `GET /api/usage` with the write key shows today's counters. `POST /internal/reset` with `{"tgId": 1001}` revokes the keys and deletes the databases. The old static keys no longer work in this mode.

## Repository modules

| Module | Purpose |
| --- | --- |
| `indexer-core` | parsing and writing the SQLite index |
| `indexer-cli` | `Indexer.jar`: indexing, `diff`, `apply`, `push` |
| `mcp-sql-service` | MCP server, REST API, keys and limits |
| `mcp-sql-bot` | Telegram bot (long polling), works through the internal API |
| `mcp-sql-web` | public website with statistics, no keys |

`./gradlew test` runs the tests of all modules, including a smoke test that starts the whole service in multi-user mode.


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
