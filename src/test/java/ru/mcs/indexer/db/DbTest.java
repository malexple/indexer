package ru.mcs.indexer.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DbTest {

    @TempDir
    Path tmp;

    private static final List<String> TABLES = List.of(
            "meta", "projects", "files", "gradle_modules", "gradle_deps", "java_modules", "module_directives",
            "types", "type_relations", "members", "annotations", "imports", "calls", "type_deps",
            "config_entries", "index_errors", "code_fts");

    private static String single(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static Path sibling(Path file, String suffix) {
        return file.resolveSibling(file.getFileName() + suffix);
    }

    @Test
    void createsSchemaAppliesPragmasAndLeavesSingleFile() throws Exception {
        Path file = tmp.resolve("sub/index.db");
        try (Db db = Db.open(file)) {
            assertThat(db.queryLong("PRAGMA foreign_keys")).isEqualTo(1);
            assertThat(db.queryString("PRAGMA journal_mode")).isEqualToIgnoringCase("wal");
            for (String table : TABLES) {
                assertThat(db.queryLong("SELECT COUNT(*) FROM sqlite_master WHERE name = ?", table))
                        .as(table).isEqualTo(1);
            }
            assertThat(db.getGlobalMeta("schema_version")).isEqualTo(String.valueOf(Schema.VERSION));
            assertThat(db.getGlobalMeta("schema_version")).isEqualTo("2");
            assertThat(db.getGlobalMeta("db_id")).isNotBlank();
            assertThat(db.getGlobalMeta("seq")).isEqualTo("0");
        }
        assertThat(file).exists();
        assertThat(sibling(file, "-wal")).doesNotExist();
        assertThat(sibling(file, "-shm")).doesNotExist();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            assertThat(single(c, "PRAGMA integrity_check")).isEqualTo("ok");
            assertThat(single(c, "PRAGMA foreign_key_check")).isNull();
            assertThat(single(c, "PRAGMA journal_mode")).isEqualToIgnoringCase("delete");
        }
    }

    @Test
    void reopeningKeepsDataAndIsIdempotent() throws Exception {
        Path file = tmp.resolve("index.db");
        String dbId;
        try (Db db = Db.open(file)) {
            db.useProject("test");
            db.putMeta("custom", "value");
            dbId = db.getGlobalMeta("db_id");
        }
        try (Db db = Db.open(file)) {
            db.useProject("test");
            assertThat(db.getMeta("custom")).isEqualTo("value");
            assertThat(db.getGlobalMeta("schema_version")).isEqualTo("2");
            assertThat(db.getGlobalMeta("db_id")).isEqualTo(dbId);
            assertThat(db.queryLong("SELECT COUNT(*) FROM projects WHERE slug = 'test'")).isEqualTo(1);
        }
    }

    @Test
    void projectMetaIsScopedAndDoesNotLeakBetweenProjects() throws Exception {
        try (Db db = Db.open(tmp.resolve("index.db"))) {
            db.useProject("one");
            db.putMeta("k", "1");
            db.useProject("two");
            assertThat(db.getMeta("k")).isNull();
            db.putMeta("k", "2");
            db.useProject("one");
            assertThat(db.getMeta("k")).isEqualTo("1");
            assertThat(db.getGlobalMeta("k")).isNull();
        }
    }

    @Test
    void seqStartsAtZeroAndBumps() throws Exception {
        try (Db db = Db.open(tmp.resolve("index.db"))) {
            assertThat(db.getGlobalMeta("seq")).isEqualTo("0");
            db.bumpSeq();
            db.bumpSeq();
            assertThat(db.getGlobalMeta("seq")).isEqualTo("2");
        }
    }

    @Test
    void projectMustBeSelectedAndNameValidated() throws Exception {
        try (Db db = Db.open(tmp.resolve("index.db"))) {
            assertThatThrownBy(db::project).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> db.getMeta("k")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> db.useProject("bad name")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> db.useProject("")).isInstanceOf(IllegalArgumentException.class);
            db.useProject("good-name_1.0");
            assertThat(db.project()).isEqualTo("good-name_1.0");
        }
    }

    @Test
    void refusesNonSqliteFile() throws Exception {
        Path file = tmp.resolve("notes.db");
        Files.writeString(file, "this is definitely not a database file", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> Db.open(file)).isInstanceOf(IllegalStateException.class);
        assertThat(Files.readString(file)).startsWith("this is definitely");
    }

    @Test
    void refusesForeignSqliteDatabase() throws Exception {
        Path file = tmp.resolve("foreign.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE x(a)");
        }
        assertThatThrownBy(() -> Db.open(file)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesOlderSchemaVersion() throws Exception {
        Path file = tmp.resolve("old.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            st.execute("INSERT INTO meta(key, value) VALUES ('schema_version', '1')");
        }
        assertThatThrownBy(() -> Db.open(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported schema_version '1'");
    }

    @Test
    void transactionRollsBackOnFailure() throws Exception {
        try (Db db = Db.open(tmp.resolve("index.db"))) {
            db.useProject("test");
            assertThatThrownBy(() -> db.inTransaction(() -> {
                db.execute("INSERT INTO meta(scope, key, value) VALUES ('test', 'k', 'v')");
                throw new SQLException("boom");
            })).isInstanceOf(SQLException.class);
            assertThat(db.getMeta("k")).isNull();
            assertThat(db.connection().getAutoCommit()).isTrue();
        }
    }

    @Test
    void foreignKeysAreEnforcedAndCascade() throws Exception {
        try (Db db = Db.open(tmp.resolve("index.db"))) {
            db.useProject("test");
            assertThatThrownBy(() -> db.execute(
                    "INSERT INTO types(file_id, fqn, simple_name, package, kind, line_from, line_to) "
                            + "VALUES (999, 'a.B', 'B', 'a', 'CLASS', 1, 2)"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> db.execute(
                    "INSERT INTO files(project_id, path, size_bytes, line_count, sha256, indexed_at) "
                            + "VALUES ('missing', 'a', 1, 1, 'x', 't')"))
                    .isInstanceOf(SQLException.class);

            db.execute("INSERT INTO files(project_id, path, size_bytes, line_count, sha256, indexed_at) "
                    + "VALUES ('test', 'a', 1, 1, 'x', 't')");
            db.execute("INSERT INTO types(file_id, fqn, simple_name, package, kind, line_from, line_to) "
                    + "VALUES (1, 'a.B', 'B', 'a', 'CLASS', 1, 2)");
            assertThat(db.queryLong("SELECT COUNT(*) FROM types")).isEqualTo(1);
            db.execute("DELETE FROM files");
            assertThat(db.queryLong("SELECT COUNT(*) FROM types")).isZero();
        }
    }

    @Test
    void deletingProjectCascadesToItsData() throws Exception {
        try (Db db = Db.open(tmp.resolve("index.db"))) {
            db.useProject("one");
            db.useProject("two");
            for (String slug : List.of("one", "two")) {
                db.execute("INSERT INTO files(project_id, path, size_bytes, line_count, sha256, indexed_at) "
                        + "VALUES ('" + slug + "', 'a', 1, 1, 'x', 't')");
            }
            db.execute("INSERT INTO types(file_id, fqn, simple_name, package, kind, line_from, line_to) "
                    + "SELECT id, 'a.B', 'B', 'a', 'CLASS', 1, 2 FROM files");
            assertThat(db.queryLong("SELECT COUNT(*) FROM types")).isEqualTo(2);

            db.execute("DELETE FROM projects WHERE slug = 'one'");

            assertThat(db.queryLong("SELECT COUNT(*) FROM files")).isEqualTo(1);
            assertThat(db.queryLong("SELECT COUNT(*) FROM types")).isEqualTo(1);
            assertThat(db.queryLong("SELECT COUNT(*) FROM files WHERE project_id = 'two'")).isEqualTo(1);
        }
    }
}