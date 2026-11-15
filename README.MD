# Indexer

Консольная утилита: обходит каталог проекта, разбирает исходники в AST и пишет структурированный индекс в один файл SQLite (`index.db`). Файл прикладывают к чату с ИИ-ассистентом: ассистент выполняет SQL (Python `sqlite3`, без интернета), находит классы, методы, вызовы, зависимости и читает исходный текст по диапазону строк.

Что в индексе: файлы с текстом, типы и члены Java (с сигнатурами и номерами строк), вызовы и зависимости между типами, импорты и аннотации, `module-info.java`, модули и зависимости Gradle, плоские ключи из yml/yaml/json/properties/toml, полнотекстовый поиск (FTS5), метаданные git.

Утилита только читает проект. Она не выполняет код проекта, не запускает сборку, не ходит в сеть;
внешний процесс только один: `git` (если он установлен).

## Требования

| Что                 | Версия                                                       |
| ------------------- | ------------------------------------------------------------ |
| JDK                 | 21 или новее                                                 |
| Gradle (для сборки) | 9.2 или новее (требование плагина Shadow 9.6.1)              |
| git                 | необязателен; с ним список файлов точно совпадает с `.gitignore` |

Платформы: сборка и работа проверены на Windows. На Linux и macOS нативных зависимостей, кроме `sqlite-jdbc` со встроенными библиотеками, нет, но сборка там пока не проверялась.

## Сборка

Wrapper-jar в репозиторий не входит. Один раз создайте его установленным Gradle:

```bash
gradlew clean test shadowJar        (Windows)
./gradlew clean test shadowJar      (Linux, macOS)
```

Результат: `build/libs/Indexer.jar`, один fat-jar (`Main-Class: ru.mcs.indexer.IndexerMain`).
Скопируйте его туда, откуда удобно запускать, например `D:\tools\Indexer.jar`.

## Быстрый старт

Из корня индексируемого проекта:

````bash
java -jar D:\tools\Indexer.jar -l=java --stats
````

Появится `.index/index.db` и рядом `.index/QUERIES.md` с готовыми запросами. Добавьте `.index/` в `.gitignore` проекта (утилита сама никогда не индексирует каталог `.index/`).

Проверить результат:

````bash
python -c "import sqlite3; c=sqlite3.connect('.index/index.db'); print(c.execute('select key,value from meta').fetchall())"
````

## Командная строка

Поддерживаются оба стиля: `-l=java` и `-l java`.

| Опция                             | По умолчанию             | Смысл                                                        |
| --------------------------------- | ------------------------ | ------------------------------------------------------------ |
| `-r`, `--root`                    | текущий каталог          | корень проекта                                               |
| `-o`, `--out`                     | `<root>/.index/index.db` | путь к базе                                                  |
| `-l`, `--lang`                    | `java`                   | языки с разбором AST через запятую: `java` (реализован), `javascript`, `zig`, `kotlin` (пока нет, будут проигнорированы с предупреждением) |
| `--include-ext`                   | пусто                    | дополнительные расширения, хранятся как текст и участвуют в FTS (точка не нужна) |
| `--exclude`                       | пусто                    | дополнительные gitignore-подобные исключения через запятую   |
| `--max-file-kb`                   | `500`                    | файлы больше пропускаются (запись `SKIP_SIZE` в `index_errors`) |
| `--incremental`                   | выкл.                    | пересчитывать только изменённые файлы (по SHA-256)           |
| `--no-source`                     | выкл.                    | не хранить текст файлов (`files.content = NULL`, FTS по тексту пустой), база заметно меньше |
| `--no-resolve`                    | выкл.                    | не использовать SymbolSolver, быстрее, все вызовы с `resolved=0` |
| `--stats`                         | выкл.                    | после индексации вывести счётчики таблиц, ошибки по стадиям, время, размер |
| `-v`, `--verbose`                 | выкл.                    | подробный лог                                                |
| `-h`, `--help`, `-V`, `--version` |                          | справка и версия                                             |

Коды выхода: `0` успех (в том числе при нефатальных ошибках разбора, они записываются в `index_errors`), `1` ошибка аргументов, `2` фатальная ошибка БД или ввода-вывода.

### Системные свойства (диагностика)

| Свойство                         | Смысл                                                        |
| -------------------------------- | ------------------------------------------------------------ |
| `-Dindexer.threads=N`            | число рабочих потоков (по умолчанию 2 при резолве, до 8 с `--no-resolve`) |
| `-Dindexer.resolve.budget.ms=N`  | бюджет CPU на резолв одного файла, по умолчанию 3000, `0` = без лимита |
| `-Dindexer.resolve.filter=false` | отключить фильтр релевантности (резолвить все вызовы, медленнее) |
| `-Dindexer.profile=true`         | с `-v` печатает профиль потоков                              |

В PowerShell аргументы с точкой берите в кавычки: `java "-Dindexer.threads=1" -jar Indexer.jar ...`.

## Примеры команд

````bash
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

:: подробный лог и профиль времени
java "-Dindexer.profile=true" -jar Indexer.jar -l=java -v --stats

:: только YAML/Gradle/конфиги и структура (AST не нужен, но язык обязателен: задайте java)
java -jar Indexer.jar -l=java --no-resolve --max-file-kb 200
````

## Что попадает в индекс

Файлы выбираются так:

1. Если в корне есть git-репозиторий и `git` доступен: `git ls-files -z --cached --others --exclude-standard`.
2. Иначе утилита сама разбирает `.gitignore` (корневой и вложенные, `.git/info/exclude`): комментарии,
   `!` отрицание, `/` якорь, каталоги, `*`, `**`, `?`, классы `[abc]`.

Всегда исключаются: `.git/`, `.gradle/`, `.idea/`, `.vscode/`, `node_modules/`, `.index/` на любой глубине,
`build/`, `out/`, `target/` вне поддеревьев `src/` (чтобы не потерять пакет с таким именем), `*.min.js`, `*.min.css`,
бинарные файлы (NUL в первых 8 КБ, запись `SKIP_BINARY`) и файлы больше `--max-file-kb`.

Расширения по умолчанию (хранятся в `files` и участвуют в FTS): `java`, `gradle`, `kts`, `properties`, `yml`,
`yaml`, `json`, `toml`, `css`, `md`, `sql`, `xml`, `txt`. AST строится только для языков из `--lang`.
Структурный разбор Gradle и конфигов включён всегда.

Текст читается как UTF-8; если файл не в UTF-8, он читается как windows-1251 и в `index_errors` пишется
предупреждение `READ`. Перевод строк нормализуется к `\n`, BOM удаляется. Все номера строк в таблицах относятся
к нормализованному тексту в `files.content`.

Стадии в `index_errors`: `READ`, `PARSE` (синтаксис и конфиги), `RESOLVE` (исчерпан бюджет резолва файла),
`SKIP_SIZE`, `SKIP_BINARY`, `DB`. Ошибка в одном файле не останавливает индексацию.

## Режимы: полный и инкрементальный

Полный проход (по умолчанию) пересоздаёт данные и перестраивает FTS; значения `files.indexed_at` у файлов
с прежним хэшем сохраняются, поэтому два запуска подряд дают одинаковые таблицы (кроме `meta.indexed_at`).

`--incremental`: читает и хэширует все файлы, парсит только новые и изменённые, удаляет исчезнувшие, ведёт FTS
вручную в одной транзакции с правкой `files`. Холостой запуск на 502 файлах занимает около 1.2 с и ничего не
меняет в базе. Модули Gradle пересчитываются каждый раз по всем скриптам.

Инкрементальный режим сам откатывается на полный проход (с предупреждением), если нет прошлого индекса,
прошлый запуск не завершился, либо изменились версия индексатора, `--lang`, `--no-source` или `--no-resolve`.

Ограничение: файлы, зависящие от изменённых, не пересчитываются, поэтому `calls.callee_owner_fqn`, `resolved`
и `type_deps` у них могут устареть. Об этом написано в `meta.notes`; полный запуск рекомендуется после каждых
100 изменённых файлов (счётчик `meta.changes_since_full`).

База после завершения всегда один файл без `-wal` и `-shm`. `VACUUM` выполняется после полного прохода
и после инкрементального, если затронуто более 20% файлов.

## Разрешение символов и скорость

`SymbolSolver` использует JDK и каталоги `src/main/java`, `src/test/java` проекта. Внешние JAR не подключаются,
поэтому вызовы библиотек (JavaFX, JUnit и т. п.) остаются с `resolved=0`; `callee_name` и строка вызова при
этом сохраняются. `resolved=1` означает, что владельца подтвердил solver.

Для скорости включён фильтр релевантности: вызов резолвится, только если метод с таким именем объявлен
в проекте. Поэтому вызовы методов JDK тоже обычно с `resolved=0`; вызовы кода проекта резолвятся полностью.

Измерено на JavaFX-проекте из 502 файлов (539 типов, 23 тыс. вызовов): полный проход 17–27 с, `--no-resolve`
около 9–13 с, база 10.3 МБ, с `--no-source` 5.7 МБ.

## Схема кратко

| Таблица                             | Содержимое                                                   |
| ----------------------------------- | ------------------------------------------------------------ |
| `meta`                              | commit, ветка, `git_dirty`, счётчики, `notes`, `indexed_at`  |
| `files`                             | путь, модуль, язык, вид (`SOURCE_MAIN`, `SOURCE_TEST`, `RESOURCE`, `BUILD`, `DOC`, `OTHER`), текст, SHA-256 |
| `types`, `type_relations`           | классы, интерфейсы, enum, record, аннотации; `extends`, `implements`, `permits` |
| `members`                           | методы, конструкторы, поля, константы enum, компоненты record, инициализаторы |
| `calls`, `type_deps`                | вызовы и создание объектов; граф «тип зависит от типа»       |
| `annotations`, `imports`            | аннотации с аргументами; импорты                             |
| `java_modules`, `module_directives` | `module-info.java`                                           |
| `gradle_modules`, `gradle_deps`     | модули и зависимости Gradle (`PROJECT` / `EXTERNAL`)         |
| `config_entries`                    | плоские ключи вроде `contributions.actions[0].id`            |
| `code_fts`                          | FTS5 по `files` (путь и текст)                               |
| `index_errors`                      | всё, что не удалось обработать                               |

Полное описание связей и готовые запросы лежат в `QUERIES.md` 

## Примеры запросов

Подставляйте свои имена. Python (без сети):

```python
import sqlite3
con = sqlite3.connect(".index/index.db")
```

Порядок работы для ассистента: сначала `meta`, затем поиск типа, затем члены и вызовы, затем текст по диапазону
строк.

Метаданные и актуальность:

```sql
SELECT key, value FROM meta ORDER BY key;
```

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

Кто реализует интерфейс:

```sql
SELECT t.fqn, f.path
FROM type_relations r JOIN types t ON t.id = r.type_id JOIN files f ON f.id = t.file_id
WHERE r.relation = 'IMPLEMENTS'
  AND (r.target_fqn = 'ru.mcs.indexer.lang.LanguageIndexer' OR r.target_name = 'LanguageIndexer');
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

От чего зависит тип и кто зависит от него:

```sql
SELECT d.dep_kind, d.to_fqn FROM type_deps d JOIN types t ON t.id = d.from_type_id
WHERE t.fqn = 'ru.mcs.indexer.db.IndexWriter' ORDER BY d.dep_kind, d.to_fqn;

SELECT t.fqn, d.dep_kind FROM type_deps d JOIN types t ON t.id = d.from_type_id
WHERE d.to_fqn = 'ru.mcs.indexer.db.Db';
```

Граф модулей Gradle и `provides` из `module-info`:

```sql
SELECT m.name, d.target FROM gradle_deps d JOIN gradle_modules m ON m.id = d.module_id
WHERE d.dep_kind = 'PROJECT' ORDER BY m.name, d.target;

SELECT m.name, d.target, d.extra FROM module_directives d JOIN java_modules m ON m.id = d.java_module_id
WHERE d.directive = 'provides';
```

Вклады плагина и сообщения:

```sql
SELECT f.path, c.key_path, c.value, c.line
FROM config_entries c JOIN files f ON f.id = c.file_id
WHERE f.path LIKE '%plugin.yml' AND c.key_path LIKE 'contributions.%';
```

Исходный текст метода по диапазону строк (в БД текст методов не дублируется):

```python
path, content, a, b = con.execute(
    "SELECT f.path, f.content, m.line_from, m.line_to FROM members m "
    "JOIN types t ON t.id = m.type_id JOIN files f ON f.id = m.file_id "
    "WHERE t.fqn = ? AND m.signature LIKE ? ORDER BY m.line_from LIMIT 1",
    ("ru.mcs.indexer.db.IndexWriter", "writeFile(%")).fetchone()
print("\n".join(content.split("\n")[a - 1:b]))
```

Полнотекстовый поиск. Пользовательский текст всегда оборачивайте в двойные кавычки (иначе точки, дефисы
и двоеточия FTS5 считает операторами), внутренние кавычки удваивайте:

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

Прогнать все примеры из `QUERIES.md` и посмотреть число строк в каждом:

````bash
python run_queries.py .index\index.db
````

## Подключение к ИИ-ассистенту

1. Постройте индекс: `java -jar Indexer.jar -l=java`.
2. Приложите к чату `.index/index.db` и `.index/QUERIES.md` (для публикации структуры без кода используйте
   `--no-source`).
3. Попросите ассистента сначала прочитать `QUERIES.md`, затем идти по цепочке: `meta`, тип, члены и вызовы,
   текст по диапазону строк.
4. После правок в проекте обновите индекс: `java -jar Indexer.jar -l=java --incremental`.

## Устранение проблем

| Симптом                                                     | Что делать                                                   |
| ----------------------------------------------------------- | ------------------------------------------------------------ |
| `Could not find or load main class .threads=1` (PowerShell) | возьмите `-D` в кавычки: `java "-Dindexer.threads=1" -jar ...` |
| `No suitable driver found`                                  | пересоберите через `shadowJar`; в конфигурации плагина обязателен `mergeServiceFiles()` (уже включён) |
| `not an SQLite database, refusing to overwrite`             | по пути `-o` лежит чужой файл; утилита ничего не перезаписывает, укажите другой путь |
| `incremental run is not possible (...)`                     | это предупреждение: опции или версия изменились, выполнен полный проход |
| Много `resolved=0`                                          | ожидаемо для вызовов JDK и библиотек; для кода проекта проверьте, что исходники лежат в `src/main/java` или `src/test/java` |
| `RESOLVE` в `index_errors`                                  | исчерпан бюджет резолва файла (3 с CPU); увеличьте `-Dindexer.resolve.budget.ms` или смиритесь с `resolved=0` |
| Прогон дольше 30 с                                          | `-Dindexer.resolve.budget.ms=1500`, затем `--no-resolve`; больше потоков не помогает: каждый поток держит собственные кэши solver'а |
| `SKIP_SIZE`                                                 | файл больше `--max-file-kb`; увеличьте лимит, если файл нужен |

## Разработка

Тесты (JUnit 5, AssertJ, `@TempDir`): `gradlew test`. Они покрывают разбор Java (типы, члены, сигнатуры, номера
строк), вызовы и резолв, `module-info`, Gradle, конфиги, `.gitignore`, лимиты размера и бинарные файлы,
синтаксические ошибки, инкрементальный режим, согласованность FTS (`integrity-check` с `rank=1`),
идемпотентность, отсутствие `-wal` и `-shm`, `foreign_key_check` и исполнение всех SQL-блоков `QUERIES.md`.

Структура: `ru.mcs.indexer` (`IndexerMain`, `IndexerApp`), `cli` (picocli), `files` (выбор файлов, `.gitignore`,
git), `db` (SQLite, транзакции, запись), `pipeline` (пул воркеров и единственный писатель), `lang` (реестр
и индексаторы: `java`, `gradle`, `config`).

Новый язык: класс, реализующий `LanguageIndexer` (метод `index(FileContext, FileFacts)` только собирает строки
в буфер и должен быть потокобезопасным), и одна строка регистрации в `LanguageRegistry`. Остальной код менять
не нужно.

## Ограничения

- Не индексируются вызовы `this(...)` и `super(...)`.
- Зависимости Gradle, записанные на нескольких строках, и `libs.*` из version catalog не распознаются.
- Резолв не видит внешние JAR (по требованию проекта).
- Инлайн-таблицы и массивы TOML хранятся сырым текстом значения.
- Языки `javascript`, `zig`, `kotlin` пока не реализованы.

## Лицензия

Исходный код распространяется по лицензии MIT, см. файл `LICENSE`.
Лицензии используемых библиотек перечислены в `THIRD-PARTY-NOTICES.md`.