# Локальная проверка mcp-sql-service

Инструкция описывает, как проверить связку **Indexer → mcp-sql-service → MCP-запросы** на своём компьютере. Примеры взяты из реальной проверки на проекте `manuscriptium`.

## Что проверяем

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

## 1. Сборка Indexer.jar

```powershell
cd D:\project\java\indexer
./gradlew clean build
Copy-Item indexer-cli\build\libs\indexer.jar D:\project\Indexer.jar
```

Проверка: `java -jar D:\project\Indexer.jar --help` показывает команды `apply`, `diff`, `push`.

## 2. Запуск mcp-sql-service

Сервис читает настройки из переменных окружения:

| Переменная | Назначение |
|---|---|
| `MCPSQL_DATA_DIR` | Каталог, где сервис хранит базы |
| `MCPSQL_READ_KEY` | Ключ для чтения (MCP-запросы) |
| `MCPSQL_WRITE_KEY` | Ключ для записи (загрузка базы и патчей) |

### Вариант А: Docker

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

### Вариант Б: jar без Docker

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

## 3. Первая индексация и загрузка базы

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

## 4. Запросы к MCP

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

### Список инструментов

```bash
curl -X POST 'http://localhost:8080/mcp' \
  -H 'Authorization: Bearer read-secret' \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  --data-raw '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

### Информация о базе

```bash
curl -X POST 'http://localhost:8080/mcp' \
  -H 'Authorization: Bearer read-secret' \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  --data-raw '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"index_info","arguments":{"workspace":"manuscriptium"}}}'
```

Пример ответа: `seq: 1`, проект `manuscriptium`, 586 файлов, 710 типов, 5378 членов, 0 ошибок.

### Модули проекта

```bash
curl -X POST 'http://localhost:8080/mcp' \
  -H 'Authorization: Bearer read-secret' \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  --data-raw '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"sql","arguments":{"workspace":"manuscriptium","query":"SELECT name, dir FROM gradle_modules ORDER BY name"}}}'
```

Ожидаемо 8 строк: `app`, `core`, `editor`, `manuscriptium` (корень, `.`), `markdown`, `metadata`, `spi`, `spi-swing`.

### Граф зависимостей между модулями

```bash
curl -X POST 'http://localhost:8080/mcp' \
  -H 'Authorization: Bearer read-secret' \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  --data-raw '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"sql","arguments":{"query":"SELECT DISTINCT m.name AS module, d.target AS depends_on FROM gradle_deps d JOIN gradle_modules m ON m.id = d.module_id WHERE d.dep_kind = \"PROJECT\" ORDER BY m.name, d.target"}}}'
```

Без `DISTINCT` одна зависимость может повторяться, если она объявлена в нескольких конфигурациях.

### Поиск типа

```bash
curl -X POST 'http://localhost:8080/mcp' \
  -H 'Authorization: Bearer read-secret' \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  --data-raw '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"find_type","arguments":{"name":"ManuscriptiumApp"}}}'
```

Пример ответа: `ru.mcs.app.ManuscriptiumApp`, `CLASS`, `app/src/main/java/ru/mcs/app/ManuscriptiumApp.java`, строки 8–19.

Дальше: `context` по `fqn` из этого ответа, затем `read_source` с `member`, `search` для текста и `callers` для вызовов.

## 5. Изменения: инкрементальный проход и патч

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

## 6. Частые ситуации

| Сообщение или симптом | Причина | Что делать |
|---|---|---|
| `patch: no data changes, no patch written` | Файлы совпадают с базой по SHA-256 (правки уже были проиндексированы раньше) | Это нормально; измените файл ещё раз |
| Патч не создаётся после полного прохода | Полный проход (`mode=full`, без `--incremental`) патч не пишет | Делайте `push --db`, потом правки и `--incremental --patch-dir` |
| `server is at seq N; no patch ... continues it` | Локальный `seq` ушёл дальше сервера, цепочка патчей разорвана | Загрузите базу целиком: `push --db`; старые патчи из `.index\patches` удалите |
| `seq` вырос на несколько единиц | Каждый запуск индексатора увеличивает `seq`, в том числе повторные полные проходы | Не запускайте лишние проходы между `push --db` и патчем |
| Новые файлы не видны | Список файлов берётся из `git ls-files` | Добавьте файл в git (`git add`) или правьте отслеживаемые |
| В `notes` «full run recommended» | После ~100 изменений у зависимых файлов могут быть устаревшие `resolved` и `type_deps` | Один полный проход и `push --db` |
| PowerShell обрывает строку в ответе | Артефакт консоли | Смотрите ответ через `ConvertFrom-Json` или в Postman |

## 7. Что проверено при локальной проверке

- Полная индексация `manuscriptium`: 586 файлов, 710 типов, 5378 членов, 0 ошибок.
- Загрузка базы целиком, `seq` на сервере совпал с локальным.
- Инструменты `index_info`, `sql` (модули и граф зависимостей), `find_type` вернули ожидаемые данные.
- Инкрементальный проход создал патч `5→6`, сервер применил его и поднялся до `seq 6`.
