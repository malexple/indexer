package ru.mcs.indexer.db;

import java.util.List;

public final class Schema {

    public static final int VERSION = 1;

    public static final List<String> WIPE_ORDER = List.of(
            "index_errors", "type_deps", "calls", "annotations", "imports", "config_entries",
            "type_relations", "members", "types", "module_directives", "java_modules",
            "gradle_deps", "gradle_modules", "files", "meta");

    public static final List<String> STATEMENTS = List.of(
            """
            CREATE TABLE IF NOT EXISTS meta (
              key TEXT PRIMARY KEY,
              value TEXT NOT NULL
            )""",
            """
            CREATE TABLE IF NOT EXISTS files (
              id INTEGER PRIMARY KEY,
              path TEXT NOT NULL UNIQUE,
              module TEXT,
              lang TEXT,
              kind TEXT,
              size_bytes INTEGER NOT NULL,
              line_count INTEGER NOT NULL,
              sha256 TEXT NOT NULL,
              content TEXT,
              indexed_at TEXT NOT NULL
            )""",
            "CREATE INDEX IF NOT EXISTS idx_files_module ON files(module)",
            "CREATE INDEX IF NOT EXISTS idx_files_lang_kind ON files(lang, kind)",
            """
            CREATE TABLE IF NOT EXISTS gradle_modules (
              id INTEGER PRIMARY KEY,
              name TEXT NOT NULL UNIQUE,
              dir TEXT NOT NULL,
              build_file TEXT
            )""",
            """
            CREATE TABLE IF NOT EXISTS gradle_deps (
              id INTEGER PRIMARY KEY,
              module_id INTEGER NOT NULL REFERENCES gradle_modules(id) ON DELETE CASCADE,
              config TEXT NOT NULL,
              dep_kind TEXT NOT NULL,
              target TEXT NOT NULL,
              line INTEGER
            )""",
            "CREATE INDEX IF NOT EXISTS idx_gradle_deps_module ON gradle_deps(module_id)",
            """
            CREATE TABLE IF NOT EXISTS java_modules (
              id INTEGER PRIMARY KEY,
              file_id INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE,
              name TEXT NOT NULL,
              is_open INTEGER NOT NULL DEFAULT 0
            )""",
            "CREATE INDEX IF NOT EXISTS idx_java_modules_file ON java_modules(file_id)",
            """
            CREATE TABLE IF NOT EXISTS module_directives (
              id INTEGER PRIMARY KEY,
              java_module_id INTEGER NOT NULL REFERENCES java_modules(id) ON DELETE CASCADE,
              directive TEXT NOT NULL,
              target TEXT NOT NULL,
              extra TEXT,
              line INTEGER
            )""",
            "CREATE INDEX IF NOT EXISTS idx_module_directives_module ON module_directives(java_module_id)",
            """
            CREATE TABLE IF NOT EXISTS types (
              id INTEGER PRIMARY KEY,
              file_id INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE,
              parent_type_id INTEGER REFERENCES types(id) ON DELETE CASCADE,
              fqn TEXT NOT NULL,
              simple_name TEXT NOT NULL,
              package TEXT NOT NULL,
              kind TEXT NOT NULL,
              modifiers TEXT NOT NULL DEFAULT '',
              is_public INTEGER NOT NULL DEFAULT 0,
              line_from INTEGER NOT NULL,
              line_to INTEGER NOT NULL,
              doc_summary TEXT
            )""",
            "CREATE INDEX IF NOT EXISTS idx_types_fqn ON types(fqn)",
            "CREATE INDEX IF NOT EXISTS idx_types_simple ON types(simple_name)",
            "CREATE INDEX IF NOT EXISTS idx_types_file ON types(file_id)",
            "CREATE INDEX IF NOT EXISTS idx_types_parent ON types(parent_type_id)",
            """
            CREATE TABLE IF NOT EXISTS type_relations (
              id INTEGER PRIMARY KEY,
              type_id INTEGER NOT NULL REFERENCES types(id) ON DELETE CASCADE,
              relation TEXT NOT NULL,
              target_name TEXT NOT NULL,
              target_fqn TEXT,
              resolved INTEGER NOT NULL DEFAULT 0
            )""",
            "CREATE INDEX IF NOT EXISTS idx_rel_target ON type_relations(target_fqn)",
            "CREATE INDEX IF NOT EXISTS idx_rel_type ON type_relations(type_id)",
            """
            CREATE TABLE IF NOT EXISTS members (
              id INTEGER PRIMARY KEY,
              type_id INTEGER NOT NULL REFERENCES types(id) ON DELETE CASCADE,
              file_id INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE,
              kind TEXT NOT NULL,
              name TEXT NOT NULL,
              signature TEXT NOT NULL,
              return_type TEXT,
              modifiers TEXT NOT NULL DEFAULT '',
              line_from INTEGER NOT NULL,
              line_to INTEGER NOT NULL,
              doc_summary TEXT
            )""",
            "CREATE INDEX IF NOT EXISTS idx_members_type ON members(type_id)",
            "CREATE INDEX IF NOT EXISTS idx_members_name ON members(name)",
            "CREATE INDEX IF NOT EXISTS idx_members_file ON members(file_id)",
            """
            CREATE TABLE IF NOT EXISTS annotations (
              id INTEGER PRIMARY KEY,
              file_id INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE,
              owner_kind TEXT NOT NULL,
              owner_id INTEGER NOT NULL,
              name TEXT NOT NULL,
              args TEXT,
              line INTEGER
            )""",
            "CREATE INDEX IF NOT EXISTS idx_ann_owner ON annotations(owner_kind, owner_id)",
            "CREATE INDEX IF NOT EXISTS idx_ann_name ON annotations(name)",
            "CREATE INDEX IF NOT EXISTS idx_ann_file ON annotations(file_id)",
            """
            CREATE TABLE IF NOT EXISTS imports (
              id INTEGER PRIMARY KEY,
              file_id INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE,
              name TEXT NOT NULL,
              is_static INTEGER NOT NULL DEFAULT 0,
              is_asterisk INTEGER NOT NULL DEFAULT 0,
              line INTEGER
            )""",
            "CREATE INDEX IF NOT EXISTS idx_imports_name ON imports(name)",
            "CREATE INDEX IF NOT EXISTS idx_imports_file ON imports(file_id)",
            """
            CREATE TABLE IF NOT EXISTS calls (
              id INTEGER PRIMARY KEY,
              file_id INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE,
              caller_member_id INTEGER REFERENCES members(id) ON DELETE CASCADE,
              call_kind TEXT NOT NULL,
              callee_name TEXT NOT NULL,
              callee_owner_fqn TEXT,
              callee_signature TEXT,
              resolved INTEGER NOT NULL DEFAULT 0,
              line INTEGER NOT NULL
            )""",
            "CREATE INDEX IF NOT EXISTS idx_calls_name ON calls(callee_name)",
            "CREATE INDEX IF NOT EXISTS idx_calls_owner ON calls(callee_owner_fqn, callee_name)",
            "CREATE INDEX IF NOT EXISTS idx_calls_caller ON calls(caller_member_id)",
            "CREATE INDEX IF NOT EXISTS idx_calls_file ON calls(file_id)",
            """
            CREATE TABLE IF NOT EXISTS type_deps (
              id INTEGER PRIMARY KEY,
              from_type_id INTEGER NOT NULL REFERENCES types(id) ON DELETE CASCADE,
              to_fqn TEXT NOT NULL,
              dep_kind TEXT NOT NULL,
              resolved INTEGER NOT NULL DEFAULT 0
            )""",
            "CREATE INDEX IF NOT EXISTS idx_deps_from ON type_deps(from_type_id)",
            "CREATE INDEX IF NOT EXISTS idx_deps_to ON type_deps(to_fqn)",
            """
            CREATE TABLE IF NOT EXISTS config_entries (
              id INTEGER PRIMARY KEY,
              file_id INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE,
              key_path TEXT NOT NULL,
              value TEXT,
              line INTEGER
            )""",
            "CREATE INDEX IF NOT EXISTS idx_cfg_key ON config_entries(key_path)",
            "CREATE INDEX IF NOT EXISTS idx_cfg_file ON config_entries(file_id)",
            """
            CREATE TABLE IF NOT EXISTS index_errors (
              id INTEGER PRIMARY KEY,
              file_id INTEGER REFERENCES files(id) ON DELETE CASCADE,
              path TEXT NOT NULL,
              stage TEXT NOT NULL,
              message TEXT NOT NULL
            )""",
            "CREATE INDEX IF NOT EXISTS idx_errors_file ON index_errors(file_id)",
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS code_fts USING fts5(
              path, content,
              content='files', content_rowid='id',
              tokenize="unicode61 tokenchars '_'"
            )""",
            "INSERT OR IGNORE INTO meta(key, value) VALUES ('schema_version', '" + VERSION + "')"
    );

    private Schema() {
    }
}