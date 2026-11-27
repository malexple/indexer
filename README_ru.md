# Indexer

Русский | [English](README.MD)

Indexer — консольная утилита: обходит каталог проекта, разбирает исходники в AST и пишет структурированный индекс в один файл SQLite (`index.db`). ИИ-ассистент работает с индексом вместо чтения всего репозитория. Есть два способа использования:

- **Приложить файл к чату.** Ассистент выполняет SQL по `index.db` (Python `sqlite3`, без интернета), находит классы, методы, вызовы и зависимости и читает исходный текст по диапазону строк.
- **Опубликовать на MCP-сервере.** `mcp-sql-service` хранит базу на сервере и отдаёт её по MCP (Streamable HTTP) в Perplexity или другой MCP-клиент. После правок кода на сервер уходит только небольшой патч.
- **Развернуть для многих пользователей.** Тот же сервис умеет многопользовательский режим: Telegram-бот (`@mcp_sql_bot`) выдаёт ключи на ограниченный срок, у каждого пользователя свои изолированные базы с лимитами, а небольшой сайт показывает публичную статистику.

Что в индексе: файлы с текстом, типы и члены Java (с сигнатурами и номерами строк), вызовы и зависимости между типами, импорты и аннотации, `module-info.java`, модули и зависимости Gradle, плоские ключи из yml/yaml/json/properties/toml, полнотекстовый поиск (FTS5), метаданные git.

Утилита только читает проект. Она не выполняет код проекта, не запускает сборку и не ходит в сеть (кроме явной команды `push`). Внешний процесс только один: `git`, если он установлен.

## Требования

| Что | Версия |
| --- | --- |
| JDK | 21 или новее |
| Gradle (для сборки) | 9.2 или новее (требование плагина Shadow 9.6.1) |
| git | необязателен; с ним список файлов точно совпадает с `.gitignore` |

Платформы: сборка и работа проверены на Windows. На Linux и macOS нативных зависимостей, кроме `sqlite-jdbc` со встроенными библиотеками, нет, но сборка там пока не проверялась.

## Сборка

Wrapper-jar в репозиторий не входит. Один раз создайте его установленным Gradle, затем:

```bash
gradlew clean test shadowJar        (Windows)
./gradlew clean test shadowJar      (Linux, macOS)
```

Результат: `indexer-cli/build/libs/Indexer.jar`, один fat-jar (`Main-Class: ru.mcs.indexer.IndexerMain`). Скопируйте его туда, откуда удобно запускать, например `D:\tools\Indexer.jar`.

## Быстрый старт

Из корня индексируемого проекта:

```bash
java -jar D:\tools\Indexer.jar -l=java --stats
```

Появится `.index/index.db` и рядом `.index/QUERIES.md` с готовыми запросами. Добавьте `.index/` в `.gitignore` проекта (утилита сама никогда не индексирует каталог `.index/`).

Проверить результат:

```bash
python -c "import sqlite3; c=sqlite3.connect('.index/index.db'); print(c.execute('select key,value from meta').fetchall())"
```

## Командная строка

Поддерживаются оба стиля: `-l=java` и `-l java`.

| Опция | По умолчанию | Смысл |
| --- | --- | --- |
| `-r`, `--root` | текущий каталог | корень проекта |
| `-o`, `--out` | `<root>/.index/index.db` | путь к базе |
| `-l`, `--lang` | `java` | языки с разбором AST через запятую: `java` (реализован); `javascript`, `zig`, `kotlin` пока нет, будут проигнорированы с предупреждением |
| `--include-ext` | пусто | дополнительные расширения, хранятся как текст и участвуют в FTS (точка не нужна) |
| `--exclude` | пусто | дополнительные gitignore-подобные исключения через запятую |
| `--max-file-kb` | `500` | файлы больше пропускаются (запись `SKIP_SIZE` в `index_errors`) |
| `--incremental` | выкл. | пересчитывать только изменённые файлы (по SHA-256) |
| `--patch-dir` | нет | вместе с `--incremental`: дополнительно записать в этот каталог патч `patch-<from>-<to>.sql.gz` для сервера |
| `--no-source` | выкл. | не хранить текст файлов (`files.content = NULL`, FTS по тексту пустой), база заметно меньше |
| `--no-resolve` | выкл. | не использовать SymbolSolver, быстрее, все вызовы с `resolved=0` |
| `--stats` | выкл. | после индексации вывести счётчики таблиц, ошибки по стадиям, время, размер |
| `-v`, `--verbose` | выкл. | подробный лог |
| `-h`, `--help`, `-V`, `--version` | | справка и версия |

Подкоманды: `push` (загрузка на MCP-сервер, см. ниже), `diff` и `apply` (создание и применение патчей между двумя базами вручную).

Коды выхода: `0` успех (в том числе при нефатальных ошибках разбора, они записываются в `index_errors`), `1` ошибка аргументов, `2` фатальная ошибка БД или ввода-вывода.

### Системные свойства (диагностика)

| Свойство | Смысл |
| --- | --- |
| `-Dindexer.threads=N` | число рабочих потоков (по умолчанию 2 при резолве, до 8 с `--no-resolve`) |
| `-Dindexer.resolve.budget.ms=N` | бюджет CPU на резолв одного файла, по умолчанию 3000, `0` = без лимита |
| `-Dindexer.resolve.filter=false` | отключить фильтр релевантности (резолвить все вызовы, медленнее) |
| `-Dindexer.profile=true` | с `-v` печатает профиль потоков |

В PowerShell аргументы с точкой берите в кавычки: `java "-Dindexer.threads=1" -jar Indexer.jar ...`.

## Примеры команд

```bash
:: индексировать текущий проект, показать сводку
java -jar Indexer.jar -l=java --stats

:: другой каталог, база в другом месте
java -jar Indexer.jar -r D:\work\myproj -o D:\work\myproj.index.db -l java

:: лёгкая версия без текста файлов (для больших проектов или публикации структуры)
java -jar Indexer.jar -l=java --no-source -o build\light.db --stats

:: быстро и без резолва типов и вызовов (около 9 с вместо 20+ с на проекте из 500 файлов)
java -jar Indexer.jar -l=java --no-resolve --stats

:: после правок: пересчитать только изменённое
java -jar Indexer.jar -l=java --incremental --stats

:: дополнительные расширения как текст, исключить сгенерированный код и тесты
java -jar Indexer.jar -l=java --include-ext=cfg,ini --exclude="/generated/,/src/test/"
```

## Что попадает в индекс

Файлы выбираются так:

1. Если в корне есть git-репозиторий и `git` доступен: `git ls-files -z --cached --others --exclude-standard`.
2. Иначе утилита сама разбирает `.gitignore` (корневой и вложенные, `.git/info/exclude`): комментарии, `!` отрицание, `/` якорь, каталоги, `*`, `**`, `?`, классы `[abc]`.

Всегда исключаются: `.git/`, `.gradle/`, `.idea/`, `.vscode/`, `node_modules/`, `.index/` на любой глубине, `build/`, `out/`, `target/` вне поддеревьев `src/` (чтобы не потерять пакет с таким именем), `*.min.js`, `*.min.css`, бинарные файлы (NUL в первых 8 КБ, запись `SKIP_BINARY`) и файлы больше `--max-file-kb`.

Расширения по умолчанию (хранятся в `files` и участвуют в FTS): `java`, `gradle`, `kts`, `properties`, `yml`, `yaml`, `json`, `toml`, `css`, `md`, `sql`, `xml`, `txt`. AST строится только для языков из `--lang`. Структурный разбор Gradle и конфигов включён всегда.

Текст читается как UTF-8; если файл не в UTF-8, он читается как windows-1251 и в `index_errors` пишется предупреждение `READ`. Перевод строк нормализуется к `\n`, BOM удаляется. Все номера строк в таблицах относятся к нормализованному тексту в `files.content`.

Стадии в `index_errors`: `READ`, `PARSE` (синтаксис и конфиги), `RESOLVE` (исчерпан бюджет резолва файла), `SKIP_SIZE`, `SKIP_BINARY`, `DB`. Ошибка в одном файле не останавливает индексацию.

## Режимы: полный и инкрементальный

Полный проход (по умолчанию) пересоздаёт данные и перестраивает FTS. Значения `files.indexed_at` у файлов с прежним хэшем сохраняются, поэтому два запуска подряд дают одинаковые таблицы (кроме `meta.indexed_at`).

`--incremental`: читает и хэширует все файлы, парсит только новые и изменённые, удаляет исчезнувшие, ведёт FTS вручную в одной транзакции с правкой `files`. Холостой запуск на 502 файлах занимает около 1.2 с и ничего не меняет в базе. Модули Gradle пересчитываются каждый раз по всем скриптам.

Инкрементальный режим сам откатывается на полный проход (с предупреждением), если нет прошлого индекса, прошлый запуск не завершился, либо изменились версия индексатора, `--lang`, `--no-source` или `--no-resolve`.

Ограничение: файлы, зависящие от изменённых, не пересчитываются, поэтому `calls.callee_owner_fqn`, `resolved` и `type_deps` у них могут устареть. Об этом написано в `meta.notes`; полный запуск рекомендуется после каждых 100 изменённых файлов (счётчик `meta.changes_since_full`).

База после завершения всегда один файл без `-wal` и `-shm`. `VACUUM` выполняется после полного прохода и после инкрементального, если затронуто более 20% файлов.

## Разрешение символов и скорость

`SymbolSolver` использует JDK и каталоги `src/main/java`, `src/test/java` проекта. Внешние JAR не подключаются, поэтому вызовы библиотек (JavaFX, JUnit и т. п.) остаются с `resolved=0`; `callee_name` и строка вызова при этом сохраняются. `resolved=1` означает, что владельца подтвердил solver.

Для скорости включён фильтр релевантности: вызов резолвится, только если метод с таким именем объявлен в проекте. Поэтому вызовы методов JDK тоже обычно с `resolved=0`; вызовы кода проекта резолвятся полностью.

Измерено на JavaFX-проекте из 502 файлов (539 типов, 23 тыс. вызовов): полный проход 17–27 с, `--no-resolve` около 9–13 с, база 10.3 МБ, с `--no-source` 5.7 МБ.

## Схема кратко

| Таблица | Содержимое |
| --- | --- |
| `meta` | commit, ветка, `git_dirty`, счётчики, `notes`, `indexed_at` |
| `files` | путь, модуль, язык, вид (`SOURCE_MAIN`, `SOURCE_TEST`, `RESOURCE`, `BUILD`, `DOC`, `OTHER`), текст, SHA-256 |
| `types`, `type_relations` | классы, интерфейсы, enum, record, аннотации; `extends`, `implements`, `permits` |
| `members` | методы, конструкторы, поля, константы enum, компоненты record, инициализаторы |
| `calls`, `type_deps` | вызовы и создание объектов; граф «тип зависит от типа» |
| `annotations`, `imports` | аннотации с аргументами; импорты |
| `java_modules`, `module_directives` | `module-info.java` |
| `gradle_modules`, `gradle_deps` | модули и зависимости Gradle (`PROJECT` / `EXTERNAL`) |
| `config_entries` | плоские ключи вроде `contributions.actions[0].id` |
| `code_fts` | FTS5 по `files` (путь и текст) |
| `index_errors` | всё, что не удалось обработать |

Полное описание связей и готовые запросы лежат в `QUERIES.md`.

## Примеры запросов

Подставляйте свои имена. Python (без сети):

```python
import sqlite3
con = sqlite3.connect(".index/index.db")
```

Порядок работы для ассистента: сначала `meta`, затем поиск типа, затем члены и вызовы, затем текст по диапазону строк.

Найти тип и его члены:

```sql
SELECT t.fqn, t.kind, f.path, t.line_from, t.line_to
FROM types t JOIN files f ON f.id = t.file_id
WHERE t.simple_name = 'IndexWriter';

SELECT m.kind, m.signature, m.line_from, m.line_to
FROM members m JOIN types t ON t.id = m.type_id
WHERE t.fqn = 'ru.mcs.indexer.db.IndexWriter'
ORDER BY m.line_from;
```

Кто вызывает метод (с файлом, строкой и вызывающим членом):

```sql
SELECT f.path, c.line, c.resolved, ct.fqn AS caller_type, cm.signature AS caller_member
FROM calls c
JOIN files f ON f.id = c.file_id
LEFT JOIN members cm ON cm.id = c.caller_member_id
LEFT JOIN types ct ON ct.id = cm.type_id
WHERE c.callee_name = 'writeFile'
ORDER BY f.path, c.line;
```

Граф модулей Gradle:

```sql
SELECT m.name, d.target FROM gradle_deps d JOIN gradle_modules m ON m.id = d.module_id
WHERE d.dep_kind = 'PROJECT' ORDER BY m.name, d.target;
```

Полнотекстовый поиск. Пользовательский текст всегда оборачивайте в двойные кавычки (иначе точки, дефисы и двоеточия FTS5 считает операторами), внутренние кавычки удваивайте:

```python
def fts_quote(text):
    return '"' + text.replace('"', '""') + '"'

rows = con.execute(
    "SELECT f.path, snippet(code_fts, 1, '[', ']', '…', 12) FROM code_fts "
    "JOIN files f ON f.id = code_fts.rowid WHERE code_fts MATCH ? LIMIT 20",
    (fts_quote("ru.mcs.Main"),)).fetchall()
```

Проверка, что база не устарела:

```python
import subprocess
indexed = dict(con.execute("SELECT key, value FROM meta")).get("git_commit", "unknown")
head = subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
if indexed != head:
    print(f"WARNING: индекс построен на {indexed[:8]}, репозиторий на {head[:8]}")
```

## Подключение к ИИ-ассистенту

### Вариант 1: приложить файл

1. Постройте индекс: `java -jar Indexer.jar -l=java`.
2. Приложите к чату `.index/index.db` и `.index/QUERIES.md` (для публикации структуры без кода используйте `--no-source`).
3. Попросите ассистента сначала прочитать `QUERIES.md`, затем идти по цепочке: `meta`, тип, члены и вызовы, текст по диапазону строк.
4. После правок в проекте обновите индекс: `java -jar Indexer.jar -l=java --incremental`.

### Вариант 2: MCP-сервер

`mcp-sql-service` — отдельный Gradle-модуль. Он хранит по одной базе SQLite на workspace и отдаёт её по MCP (Streamable HTTP, адрес `/mcp`). У сервиса два ключа: ключ чтения для MCP-клиентов и ключ записи для загрузки баз и патчей.

Инструменты MCP (все только для чтения):

| Инструмент | Назначение |
| --- | --- |
| `index_info` | Начните с него: проекты, коммит, ветка, счётчики, замечания об устаревании; `includeSchema=true` добавляет список таблиц |
| `find_type` | Поиск типа по простому имени, fqn или суффиксу |
| `context` | Обзор типа одним вызовом: расположение, супертипы, подтипы, члены, зависимости, зависимые, resolved-вызывающие |
| `read_source` | Исходный текст типа или членов по префиксу сигнатуры |
| `read_lines` | Строки `from..to` файла (до 400 за вызов) |
| `search` | Полнотекстовый поиск по текстовым файлам |
| `callers` | Где вызывается метод |
| `sql` | Один SELECT или WITH по индексу, только чтение |

Сервис читает настройки из переменных окружения:

| Переменная | Смысл |
| --- | --- |
| `MCPSQL_DATA_DIR` | каталог, где сервис хранит базы (`/data` в Docker) |
| `MCPSQL_READ_KEY` | ключ для MCP-клиентов (Bearer) |
| `MCPSQL_WRITE_KEY` | ключ для `push` (база и патчи) |
| `MCPSQL_DOMAIN` | домен для Caddy и HTTPS (только Docker Compose) |

#### Адреса

| Адрес | Что отдаёт |
| --- | --- |
| `/mcp` | Все workspace владельца ключа; при нескольких нужен аргумент `workspace` |
| `/mcp/{workspace}` | Один workspace; аргумент `workspace` подставляет сервер |
| `/mcp/{workspace}/{project}` | Один проект внутри workspace; `workspace` и `project` подставляет сервер |

На двух привязанных адресах главные значения из пути: вызов инструмента с другим workspace или проектом отклоняется. Неизвестный workspace отвечает `404 not found`.

REST API (ключ записи): `GET /api/workspaces`, `GET /api/workspaces/{workspace}`, `PUT /api/workspaces/{workspace}/db`, `POST /api/workspaces/{workspace}/patch`, `DELETE /api/workspaces/{workspace}` и в многопользовательском режиме `GET /api/usage` (счётчики за сегодня и лимиты). `GET /health` ключа не требует.

#### Режим одного пользователя и многопользовательский режим

По умолчанию (`MCPSQL_MULTI_ENABLED=false`) у сервиса один владелец и два статических ключа из окружения (`MCPSQL_READ_KEY`, `MCPSQL_WRITE_KEY`); базы лежат прямо в `MCPSQL_DATA_DIR`. Это режим для своего компьютера или личного сервера.

При `MCPSQL_MULTI_ENABLED=true` статические ключи не используются. Ключи выдаёт Telegram-бот на каждого пользователя, у каждого свой каталог `users/{id}/` внутри каталога данных, действуют лимиты ниже.

#### Общий многопользовательский сервис

Как работает пользователь:

1. Откройте бота (`@mcp_sql_bot`), нажмите **Получить ключи** и выберите срок: 5 часов, сутки или неделя. Бот пришлёт два ключа. Ключ записи (`mcp_w_...`) ваш: загрузка, патчи, удаление; он же читает. Ключ чтения (`mcp_r_...`) для ИИ-ассистента, только чтение. Оба показываются один раз, сервер хранит только их хеши SHA-256.
2. Загрузите индекс ключом записи (`push`, см. ниже).
3. Дайте ИИ-ассистенту ключ чтения и адрес `https://<домен>/mcp/<workspace>`.

В меню бота есть также **Мои данные** (срок ключей, базы, счётчики за сегодня и лимиты), **Статистика**, **Помощь** и **Удалить всё**. Последнее сразу отзывает ключи и удаляет базы пользователя. Оно доступно всегда, в том числе при исчерпанном дневном лимите выдач, и возвращает выдачи за день: у пользователя, чьи ключи утекли, всегда есть возможность начать заново.

Жизнь данных: новая пара ключей заменяет прежнюю и продлевает жизнь баз пользователя. Если ключи истекли без продления, базы удаляются автоматически (примерно в течение минуты), затем удаляются записи истёкших ключей. Запись пользователя (Telegram id) и дневные счётчики остаются.

Лимиты (сутки считаются по UTC, отклонённые загрузки тоже считаются):

| Лимит | По умолчанию | Переменная |
| --- | --- | --- |
| Пользователей с живыми ключами одновременно | 50 | `MCPSQL_MAX_ACTIVE_USERS` |
| Выдач ключей на пользователя в сутки | 5 | `MCPSQL_KEY_ISSUES_PER_DAY` |
| Workspace на пользователя | 3 | `MCPSQL_MAX_WORKSPACES` |
| Размер одной базы (в том числе после патча) | 20 МБ | `MCPSQL_MAX_DB_MB` |
| Загрузок базы в сутки | 10 | `MCPSQL_UPLOADS_PER_DAY` |
| Объём загрузок в сутки (базы и патчи вместе) | 300 МБ | `MCPSQL_WRITE_MB_PER_DAY` |
| Пауза между патчами одного пользователя | 30 с | `MCPSQL_PATCH_INTERVAL_SECONDS` |
| Запросов MCP (POST на `/mcp`) в сутки | 5000 | `MCPSQL_REQUESTS_PER_DAY` |
| Свободное место на диске, ниже которого загрузки отклоняются | 2048 МБ | `MCPSQL_MIN_FREE_DISK_MB` |

Отказы: `401` (нет ключа, ключ неверный, истёк или отозван, либо ключ чтения там, где нужен ключ записи), `404` (неизвестный workspace; одинаковый ответ для несуществующего и чужого), `409` (лимит workspace), `429` с JSON-полем `code` (`KEY_ISSUE_LIMIT`, `UPLOAD_LIMIT`, `WRITE_BYTES_LIMIT`, `PATCH_INTERVAL`, `REQUEST_LIMIT`), `503` (сервер заполнен при выдаче ключей), `507` (мало места на диске).

Бот и сайт: отдельные модули (см. «Модули репозитория»). Бот не хранит ключей и работает через `/internal/*`: путь требует заголовок `X-Internal-Key` и никогда не публикуется наружу. Сайт показывает только сводную статистику: пользователи, пары ключей по срокам, занятое и свободное место, лимиты.

#### Развёртывание на сервере

На сервере работают четыре контейнера: `mcp-sql` (сервис), `mcp-sql-bot`, `mcp-sql-web` и `caddy` (HTTPS и маршрутизация). Открыты только порты 80 и 443 у Caddy. Файлы лежат в `mcp-sql-service/`: `docker-compose.yml`, `Caddyfile` и `.env.example`; пошаговая инструкция: [`mcp-sql-service/DEPLOY.md`](mcp-sql-service/DEPLOY.md).

`Caddyfile` (сервису достаются MCP и API пользователя, сайту всё остальное, `/internal` не публикуется):

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

Главные настройки `.env` (остальное в `.env.example`):

| Переменная | Смысл |
| --- | --- |
| `MCPSQL_DOMAIN` | домен для Caddy и HTTPS |
| `MCPSQL_TAG` | версия образов (`malexple/mcp-sql`, `malexple/mcp-sql-bot`, `malexple/mcp-sql-web`) |
| `MCPSQL_MULTI_ENABLED` | `true` для многопользовательского режима |
| `MCPSQL_INTERNAL_KEY` | секрет между ботом и сервисом (`openssl rand -hex 32`) |
| `BOT_TOKEN` | токен от BotFather |
| `BOT_PUBLIC_URL` | адрес, который бот показывает пользователям |

Перед первым запуском направьте DNS-запись A домена на сервер и откройте TCP 80 и 443. Затем `docker compose pull`, `docker compose up -d` и `docker compose logs`. Не выполняйте `docker compose down -v`: оно удалит тома с базами и сертификатами. Проверьте `https://<домен>/internal/stats`: должен быть ответ 404.

Выпуск версии: `.\scripts\publish.ps1 -Version 0.1.1` (Linux и macOS: `scripts/publish.sh 0.1.1`) запускает тесты, собирает три образа под linux/amd64 и публикует их; `-NoPush` только собирает. После этого на сервере задайте `MCPSQL_TAG` и выполните `docker compose pull` и `docker compose up -d`. Откат: верните прежний тег.


#### Загрузка индекса

```powershell
$env:MCPSQL_WRITE_KEY="<ключ записи>"
java -jar Indexer.jar push --server https://example.com --workspace myproject --db .index\index.db
```

Сервер отвечает `database uploaded: {... "seq":N ...}`.

#### Обновление патчами

Каждый запуск индексатора увеличивает `seq`. Патч применяется, только если продолжает `seq` сервера, поэтому после полного прохода всегда сначала загружайте базу целиком.

```powershell
java -jar Indexer.jar -l=java --incremental --patch-dir .index\patches --stats
java -jar Indexer.jar push --server https://example.com --workspace myproject --patch-dir .index\patches
```

Ожидаемо: `patch: ...patch-5-6.sql.gz (seq 5 -> 6, ...)`, затем `applied patch-5-6.sql.gz` и `server is now at seq 6`. Если последовательность разошлась (`server is at seq N; no patch ... continues it`), загрузите базу заново через `push --db`.

#### Подключение к Perplexity

1. Откройте **Plugins → Add custom → Add MCP connector → Remote** (кастомные коннекторы доступны на тарифах Pro, Max и Enterprise).
2. Имя `Indexer`, MCP server URL `https://example.com/mcp`.
3. В **Advanced** выберите Authentication `API Key` с ключом **чтения**, Transport `Streamable HTTP`, Network access `Public`.
4. Отметьте подтверждение рисков, нажмите **Add**, затем нажмите на карточку коннектора, чтобы включить его.
5. В новом треде включите коннектор и спросите, например, «покажи index_info» или «найди тип IndexWriter».

Используйте только ключ чтения. Ключ записи нельзя отдавать MCP-клиенту.

В общем сервисе используйте ключ чтения из бота и адрес `https://<домен>/mcp/<workspace>` вместо `/mcp`.


## Локальная проверка mcp-sql-service

Инструкция описывает, как проверить связку **Indexer → mcp-sql-service → MCP-запросы** на своём компьютере. Примеры взяты из реальной проверки на проекте `manuscriptium`.

### Что проверяем

1. Индексатор строит SQLite-базу проекта (`index.db`).
2. База загружается на сервер целиком (`push --db`).
3. После правок индексатор делает инкрементальный проход и пишет патч (`--patch-dir`).
4. Патч применяется на сервере (`push --patch-dir`).
5. Данные читаются через MCP (Postman или `curl`).

Условные обозначения путей:

| Что | Путь |
|---|---|
| Репозиторий индексатора | `D:\project\java\indexer` |
| Собранный jar индексатора | `D:\project\Indexer.jar` |
| Проект для индексации | `D:\project\java\manuscriptium` |
| Сервер | `http://localhost:8080` |
| Ключ чтения / записи | `read-secret` / `write-secret` (только для локальной проверки) |
| Workspace на сервере | `manuscriptium` |

### 1. Сборка Indexer.jar

```powershell
cd D:\project\java\indexer
./gradlew clean build
Copy-Item indexer-cli\build\libs\indexer.jar D:\project\Indexer.jar
```

Проверка: `java -jar D:\project\Indexer.jar --help` показывает команды `apply`, `diff`, `push`.

### 2. Запуск mcp-sql-service

Сервис читает настройки из переменных окружения:

| Переменная | Назначение |
|---|---|
| `MCPSQL_DATA_DIR` | Каталог, где сервис хранит базы |
| `MCPSQL_READ_KEY` | Ключ для чтения (MCP-запросы) |
| `MCPSQL_WRITE_KEY` | Ключ для записи (загрузка базы и патчей) |

#### Вариант А: Docker

```powershell
cd D:\project\java\indexer\mcp-sql-service
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d mcp-sql
docker compose logs mcp-sql
```

Файл `.env` рядом с `docker-compose.yml`:

```
MCPSQL_DOMAIN=localhost
MCPSQL_DATA_DIR=/data
MCPSQL_READ_KEY=read-secret
MCPSQL_WRITE_KEY=write-secret
```

Если Docker Desktop выдаёт `request returned Internal Server Error for API route`, перезапустите движок Docker Desktop, выполните `wsl --update` и `wsl --shutdown`, затем снова запустите Docker Desktop. Пока `docker version` не покажет и клиента, и сервер, образ не запустится.

#### Вариант Б: jar без Docker

```powershell
cd D:\project\java\indexer
./gradlew :mcp-sql-service:bootJar
$env:MCPSQL_DATA_DIR="D:\project\mcp-data"
$env:MCPSQL_READ_KEY="read-secret"
$env:MCPSQL_WRITE_KEY="write-secret"
java -jar mcp-sql-service\build\libs\app.jar
```

Запускайте в отдельном окне PowerShell. Имя `app.jar` задаётся в `mcp-sql-service/build.gradle`:

```groovy
tasks.named('bootJar') { archiveFileName = 'app.jar' }
tasks.named('jar') { enabled = false }
```

### 3. Первая индексация и загрузка базы

Из каталога проекта (`D:\project\java\manuscriptium`):

```powershell
java -jar ..\..\Indexer.jar -r . -l=java --stats
```

В журнале ожидайте `mode=full`, `failed=0`, `seq=1` и список статистики (файлы, типы, члены, вызовы, Gradle-модули). База появится в `.index\index.db`.

Загрузка базы целиком:

```powershell
$env:MCPSQL_WRITE_KEY="write-secret"
java -jar ..\..\Indexer.jar push --server http://localhost:8080 --workspace manuscriptium --db .index\index.db
```

Ответ: `database uploaded: {"workspace":"manuscriptium","dbId":"...","seq":1,"projects":["manuscriptium"]}`.

### 4. Запросы к MCP

Все запросы: `POST http://localhost:8080/mcp` со следующими заголовками.

```
Authorization: Bearer read-secret
Content-Type: application/json
Accept: application/json, text/event-stream
```

Ответ приходит в `result.content[0].text`. Список инструментов (`tools/list`), их аргументов и описаний:

| Инструмент | Что делает | Обязательные аргументы |
|---|---|---|
| `index_info` | Проекты workspace, коммит, ветка, счётчики, замечания об устаревании; `includeSchema=true` добавляет список таблиц | нет |
| `find_type` | Поиск типа по простому или полному имени | `name` |
| `context` | Обзор типа: члены, зависимости, зависимые, вызывающие | `fqn` |
| `read_source` | Исходный текст типа или членов по префиксу сигнатуры | `fqn` (и `member`) |
| `read_lines` | Строки файла по пути (до 400 за вызов) | `path`, `from`, `to` |
| `search` | Полнотекстовый поиск по текстовым файлам | `text` |
| `callers` | Где вызывается метод | `name` |
| `sql` | Один SELECT или WITH по индексу; ответ обрезается по размеру | `query` |

Аргумент `workspace` необязателен, пока на сервере одно рабочее пространство. Аргумент `project` необязателен, без него поиск идёт по всем проектам.

#### Список инструментов

```bash
curl -X POST 'http://localhost:8080/mcp' \
  -H 'Authorization: Bearer read-secret' \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  --data-raw '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

#### Информация о базе

```bash
curl -X POST 'http://localhost:8080/mcp' \
  -H 'Authorization: Bearer read-secret' \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  --data-raw '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"index_info","arguments":{"workspace":"manuscriptium"}}}'
```

Пример ответа: `seq: 1`, проект `manuscriptium`, 586 файлов, 710 типов, 5378 членов, 0 ошибок.

#### Модули проекта

```bash
curl -X POST 'http://localhost:8080/mcp' \
  -H 'Authorization: Bearer read-secret' \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  --data-raw '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"sql","arguments":{"workspace":"manuscriptium","query":"SELECT name, dir FROM gradle_modules ORDER BY name"}}}'
```

Ожидаемо 8 строк: `app`, `core`, `editor`, `manuscriptium` (корень, `.`), `markdown`, `metadata`, `spi`, `spi-swing`.

#### Граф зависимостей между модулями

```bash
curl -X POST 'http://localhost:8080/mcp' \
  -H 'Authorization: Bearer read-secret' \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  --data-raw '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"sql","arguments":{"query":"SELECT DISTINCT m.name AS module, d.target AS depends_on FROM gradle_deps d JOIN gradle_modules m ON m.id = d.module_id WHERE d.dep_kind = \"PROJECT\" ORDER BY m.name, d.target"}}}'
```

Без `DISTINCT` одна зависимость может повторяться, если она объявлена в нескольких конфигурациях.

#### Поиск типа

```bash
curl -X POST 'http://localhost:8080/mcp' \
  -H 'Authorization: Bearer read-secret' \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  --data-raw '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"find_type","arguments":{"name":"ManuscriptiumApp"}}}'
```

Пример ответа: `ru.mcs.app.ManuscriptiumApp`, `CLASS`, `app/src/main/java/ru/mcs/app/ManuscriptiumApp.java`, строки 8–19.

Дальше: `context` по `fqn` из этого ответа, затем `read_source` с `member`, `search` для текста и `callers` для вызовов.

### 5. Изменения: инкрементальный проход и патч

Важно: порядок строгий. Патч применяется только если он продолжает версию (`seq`) сервера.

1. Убедитесь, что сервер и локальная база на одном `seq`: после полного прохода всегда делайте `push --db`.
2. Сохраните копию базы (нужна для команды `diff`):

```powershell
Copy-Item .index\index.db .index\base.db
```

3. Измените файлы проекта.
4. Запустите инкрементальный проход с записью патча:

```powershell
java -jar ..\..\Indexer.jar -r . -l=java --incremental --patch-dir .index\patches --stats
```

Ожидаемо: `mode=incremental`, `written=<число изменённых файлов>` и строка вида `patch: ...\.index\patches\patch-5-6.sql.gz (seq 5 -> 6, -141 +140 ~12 rows, 5 KB)`.

5. Отправьте патч:

```powershell
java -jar ..\..\Indexer.jar push --server http://localhost:8080 --workspace manuscriptium --patch-dir .index\patches
```

Ожидаемо: `applied patch-5-6.sql.gz: {"workspace":"manuscriptium","fromSeq":5,"toSeq":6}` и `server is now at seq 6`.

6. Проверьте через MCP: `index_info` (новый `seq`, поле `notes`) и `search` по тексту, который изменили.

### 6. Частые ситуации

| Сообщение или симптом | Причина | Что делать |
|---|---|---|
| `patch: no data changes, no patch written` | Файлы совпадают с базой по SHA-256 (правки уже были проиндексированы раньше) | Это нормально; измените файл ещё раз |
| Патч не создаётся после полного прохода | Полный проход (`mode=full`, без `--incremental`) патч не пишет | Делайте `push --db`, потом правки и `--incremental --patch-dir` |
| `server is at seq N; no patch ... continues it` | Локальный `seq` ушёл дальше сервера, цепочка патчей разорвана | Загрузите базу целиком: `push --db`; старые патчи из `.index\patches` удалите |
| `seq` вырос на несколько единиц | Каждый запуск индексатора увеличивает `seq`, в том числе повторные полные проходы | Не запускайте лишние проходы между `push --db` и патчем |
| Новые файлы не видны | Список файлов берётся из `git ls-files` | Добавьте файл в git (`git add`) или правьте отслеживаемые |
| В `notes` «full run recommended» | После ~100 изменений у зависимых файлов могут быть устаревшие `resolved` и `type_deps` | Один полный проход и `push --db` |
| PowerShell обрывает строку в ответе | Артефакт консоли | Смотрите ответ через `ConvertFrom-Json` или в Postman |

### 7. Что проверено при локальной проверке

- Полная индексация `manuscriptium`: 586 файлов, 710 типов, 5378 членов, 0 ошибок.
- Загрузка базы целиком, `seq` на сервере совпал с локальным.
- Инструменты `index_info`, `sql` (модули и граф зависимостей), `find_type` вернули ожидаемые данные.
- Инкрементальный проход создал патч `5→6`, сервер применил его и поднялся до `seq 6`.

### 8. Многопользовательский режим локально

1. Добавьте в `.env`: `MCPSQL_MULTI_ENABLED=true`, `MCPSQL_INTERNAL_KEY=internal-secret`, `BOT_TOKEN=<токен>` (нужен только боту) и `BOT_PUBLIC_URL=http://localhost:8080`.
2. Соберите все jar и запустите всё: `./gradlew :mcp-sql-service:bootJar :mcp-sql-bot:bootJar :mcp-sql-web:bootJar`, затем `docker compose -f docker-compose.yml -f docker-compose.local.yml up -d --build`. Сайт открывается на `http://127.0.0.1:8081`.
3. Получите ключи без бота:

```bash
curl -X POST http://localhost:8080/internal/keys \
  -H 'Content-Type: application/json' -H 'X-Internal-Key: internal-secret' \
  --data '{"tgId": 1001, "ttl": "1d"}'
```

4. Загрузите базу полученным `writeKey` (`MCPSQL_WRITE_KEY`) и обращайтесь к `http://localhost:8080/mcp/manuscriptium` с `readKey`. Второй пользователь (другой `tgId`) по тому же адресу получит `404 not found`.
5. `GET /api/usage` с ключом записи показывает счётчики за сегодня. `POST /internal/reset` с телом `{"tgId": 1001}` отзывает ключи и удаляет базы. Старые статические ключи в этом режиме не работают.

## Модули репозитория

| Модуль | Назначение |
| --- | --- |
| `indexer-core` | разбор проекта и запись индекса SQLite |
| `indexer-cli` | `Indexer.jar`: индексация, `diff`, `apply`, `push` |
| `mcp-sql-service` | MCP-сервер, REST API, ключи и лимиты |
| `mcp-sql-bot` | Telegram-бот (long polling), работает через внутренний API |
| `mcp-sql-web` | публичный сайт со статистикой, без ключей |

`./gradlew test` запускает тесты всех модулей, в том числе дымовой тест, который поднимает весь сервис в многопользовательском режиме.


## Устранение проблем

| Симптом | Что делать |
| --- | --- |
| `Could not find or load main class .threads=1` (PowerShell) | возьмите `-D` в кавычки: `java "-Dindexer.threads=1" -jar ...` |
| `No suitable driver found` | пересоберите через `shadowJar`; в конфигурации плагина обязателен `mergeServiceFiles()` (уже включён) |
| `not an SQLite database, refusing to overwrite` | по пути `-o` лежит чужой файл; утилита ничего не перезаписывает, укажите другой путь |
| `incremental run is not possible (...)` | это предупреждение: опции или версия изменились, выполнен полный проход |
| Много `resolved=0` | ожидаемо для вызовов JDK и библиотек; для кода проекта проверьте, что исходники лежат в `src/main/java` или `src/test/java` |
| `RESOLVE` в `index_errors` | исчерпан бюджет резолва файла (3 с CPU); увеличьте `-Dindexer.resolve.budget.ms` или смиритесь с `resolved=0` |
| Прогон дольше 30 с | `-Dindexer.resolve.budget.ms=1500`, затем `--no-resolve`; больше потоков не помогает: каждый поток держит собственные кэши solver'а |
| `SKIP_SIZE` | файл больше `--max-file-kb`; увеличьте лимит, если файл нужен |
| `patch: no data changes, no patch written` | хэши файлов совпадают с базой; измените файл и запустите ещё раз |
| `server is at seq N; no patch ... continues it` | загрузите базу целиком через `push --db` и удалите старые патчи |
| Новые файлы не попадают в индекс | список берётся из `git ls-files`; добавьте файл в git (`git add`) |
| MCP-клиент получает 401 | проверьте ключ чтения и заголовок `Authorization: Bearer`; смотрите `docker compose logs mcp-sql` |

## Разработка

Тесты (JUnit 5, AssertJ, `@TempDir`): `gradlew test`. Они покрывают разбор Java (типы, члены, сигнатуры, номера строк), вызовы и резолв, `module-info`, Gradle, конфиги, `.gitignore`, лимиты размера и бинарные файлы, синтаксические ошибки, инкрементальный режим, согласованность FTS (`integrity-check` с `rank=1`), идемпотентность, отсутствие `-wal` и `-shm`, `foreign_key_check` и исполнение всех SQL-блоков `QUERIES.md`.

Структура: `ru.mcs.indexer` (`IndexerMain`, `IndexerApp`), `cli` (picocli), `files` (выбор файлов, `.gitignore`, git), `db` (SQLite, транзакции, запись), `pipeline` (пул воркеров и единственный писатель), `lang` (реестр и индексаторы: `java`, `gradle`, `config`).

Новый язык: класс, реализующий `LanguageIndexer` (метод `index(FileContext, FileFacts)` только собирает строки в буфер и должен быть потокобезопасным), и одна строка регистрации в `LanguageRegistry`. Остальной код менять не нужно.

## Ограничения

- Не индексируются вызовы `this(...)` и `super(...)`.
- Зависимости Gradle, записанные на нескольких строках, и `libs.*` из version catalog не распознаются.
- Резолв не видит внешние JAR (по требованию проекта).
- Инлайн-таблицы и массивы TOML хранятся сырым текстом значения.
- Языки `javascript`, `zig`, `kotlin` пока не реализованы.
- MCP-сервис для клиентов только читает: изменить ваш код он не может.

## Лицензия

Исходный код распространяется по лицензии MIT, см. файл `LICENSE`. Лицензии используемых библиотек перечислены в `THIRD-PARTY-NOTICES.md`.
