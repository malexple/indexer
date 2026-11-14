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
            "meta", "files", "gradle_modules", "gradle_deps", "java_modules", "module_directives",
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
            assertThat(db.getMeta("schema_version")).isEqualTo("1");
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
        try (Db db = Db.open(file)) {
            db.putMeta("custom", "value");
        }
        try (Db db = Db.open(file)) {
            assertThat(db.getMeta("custom")).isEqualTo("value");
            assertThat(db.getMeta("schema_version")).isEqualTo("1");
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
    void transactionRollsBackOnFailure() throws Exception {
        try (Db db = Db.open(tmp.resolve("index.db"))) {
            assertThatThrownBy(() -> db.inTransaction(() -> {
                db.execute("INSERT INTO meta(key, value) VALUES ('k', 'v')");
                throw new SQLException("boom");
            })).isInstanceOf(SQLException.class);
            assertThat(db.getMeta("k")).isNull();
            assertThat(db.connection().getAutoCommit()).isTrue();
        }
    }

    @Test
    void foreignKeysAreEnforcedAndCascade() throws Exception {
        try (Db db = Db.open(tmp.resolve("index.db"))) {
            assertThatThrownBy(() -> db.execute(
                    "INSERT INTO types(file_id, fqn, simple_name, package, kind, line_from, line_to) "
                            + "VALUES (999, 'a.B', 'B', 'a', 'CLASS', 1, 2)"))
                    .isInstanceOf(SQLException.class);

            db.execute("INSERT INTO files(path, size_bytes, line_count, sha256, indexed_at) "
                    + "VALUES ('a', 1, 1, 'x', 't')");
            db.execute("INSERT INTO types(file_id, fqn, simple_name, package, kind, line_from, line_to) "
                    + "VALUES (1, 'a.B', 'B', 'a', 'CLASS', 1, 2)");
            assertThat(db.queryLong("SELECT COUNT(*) FROM types")).isEqualTo(1);
            db.execute("DELETE FROM files");
            assertThat(db.queryLong("SELECT COUNT(*) FROM types")).isZero();
        }
    }
}