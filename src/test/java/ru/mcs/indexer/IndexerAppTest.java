package ru.mcs.indexer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.mcs.indexer.files.GitRunner;
import ru.mcs.indexer.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IndexerAppTest {

    private static final String FTS_SQL =
            "SELECT f.path FROM code_fts JOIN files f ON f.id = code_fts.rowid "
                    + "WHERE code_fts MATCH ? ORDER BY f.path";

    @TempDir
    Path tmp;

    private Path db() {
        return tmp.resolve(".index/index.db");
    }

    private void write(String rel, byte[] content) throws IOException {
        Path file = tmp.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.write(file, content);
    }

    private void write(String rel, String content) throws IOException {
        write(rel, content.getBytes(StandardCharsets.UTF_8));
    }

    private void createProject() throws IOException {
        write("src/main/java/ru/mcs/Main.java", "\uFEFFpackage ru.mcs;\r\n\r\npublic class Main {}\r\n");
        write("README.md", "# Demo\nsee ru.mcs.Main and foo_bar_baz and uniqueTokenXyz\n");
        write("big.txt", "x".repeat(2048));
        write("bin.txt", new byte[]{'a', 0, 'b'});
        write("legacy.txt", new byte[]{(byte) 0xCF, (byte) 0xF0, (byte) 0xE8, (byte) 0xE2, (byte) 0xE5, (byte) 0xF2});
    }

    private int run(boolean noSource) throws IOException {
        IndexerOptions options = new IndexerOptions(tmp, db(), Set.of("java"),
                IndexerOptions.DEFAULT_EXTENSIONS, List.of(), 1, false, noSource, false, true, false);
        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        return IndexerApp.run(options, new Log(false, sink, sink), GitRunner.disabled());
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + db());
    }

    private static List<List<Object>> dump(Connection c, String sql) throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    row.add(rs.getObject(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static List<String> column(Connection c, String sql, String param) throws SQLException {
        List<String> values = new ArrayList<>();
        try (var ps = c.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    values.add(rs.getString(1));
                }
            }
        }
        return values;
    }

    @Test
    void indexesFilesWithoutAst() throws Exception {
        createProject();
        assertThat(run(false)).isZero();

        try (Connection c = connect()) {
            assertThat(dump(c, "SELECT path FROM files ORDER BY path")).containsExactly(
                    List.of("README.md"), List.of("legacy.txt"), List.of("src/main/java/ru/mcs/Main.java"));

            List<List<Object>> main = dump(c,
                    "SELECT lang, kind, line_count, content, module FROM files WHERE path LIKE '%Main.java'");
            assertThat(main).hasSize(1);
            assertThat(main.get(0).get(0)).isEqualTo("java");
            assertThat(main.get(0).get(1)).isEqualTo("SOURCE_MAIN");
            assertThat(((Number) main.get(0).get(2)).intValue()).isEqualTo(3);
            assertThat(main.get(0).get(3)).isEqualTo("package ru.mcs;\n\npublic class Main {}\n");
            assertThat(main.get(0).get(4)).isNull();

            assertThat(dump(c, "SELECT stage, path, file_id IS NULL FROM index_errors ORDER BY stage, path"))
                    .containsExactly(
                            List.of("READ", "legacy.txt", 0),
                            List.of("SKIP_BINARY", "bin.txt", 1),
                            List.of("SKIP_SIZE", "big.txt", 1));

            assertThat(dump(c, "SELECT value FROM meta WHERE key = 'schema_version'")).containsExactly(List.of("1"));
            assertThat(dump(c, "SELECT value FROM meta WHERE key = 'file_count'")).containsExactly(List.of("3"));
            assertThat(dump(c, "SELECT value FROM meta WHERE key = 'error_count'")).containsExactly(List.of("3"));
            assertThat(dump(c, "SELECT value FROM meta WHERE key = 'source_included'")).containsExactly(List.of("1"));
            assertThat(dump(c, "SELECT value FROM meta WHERE key = 'git_commit'")).containsExactly(List.of("unknown"));
            assertThat(dump(c, "SELECT value FROM meta WHERE key = 'languages'")).containsExactly(List.of("java"));
        }
    }

    @Test
    void fullTextSearchFindsQuotedTokens() throws Exception {
        createProject();
        run(false);
        try (Connection c = connect()) {
            assertThat(column(c, FTS_SQL, "\"uniqueTokenXyz\"")).containsExactly("README.md");
            assertThat(column(c, FTS_SQL, "\"foo_bar_baz\"")).containsExactly("README.md");
            assertThat(column(c, FTS_SQL, "\"missingTokenQwerty\"")).isEmpty();
        }
    }

    @Test
    void noSourceDropsContentAndTextSearch() throws Exception {
        createProject();
        assertThat(run(true)).isZero();
        try (Connection c = connect()) {
            assertThat(dump(c, "SELECT COUNT(*) FROM files WHERE content IS NOT NULL")).containsExactly(List.of(0));
            assertThat(dump(c, "SELECT SUM(line_count) FROM files")).isNotEmpty();
            assertThat(column(c, FTS_SQL, "\"uniqueTokenXyz\"")).isEmpty();
            assertThat(dump(c, "SELECT value FROM meta WHERE key = 'source_included'")).containsExactly(List.of("0"));
        }
    }

    @Test
    void secondRunProducesIdenticalData() throws Exception {
        createProject();
        run(false);
        List<List<Object>> files1;
        List<List<Object>> errors1;
        List<List<Object>> meta1;
        try (Connection c = connect()) {
            files1 = dump(c, "SELECT * FROM files ORDER BY id");
            errors1 = dump(c, "SELECT * FROM index_errors ORDER BY id");
            meta1 = dump(c, "SELECT key, value FROM meta WHERE key <> 'indexed_at' ORDER BY key");
        }
        run(false);
        try (Connection c = connect()) {
            assertThat(dump(c, "SELECT * FROM files ORDER BY id")).isEqualTo(files1);
            assertThat(dump(c, "SELECT * FROM index_errors ORDER BY id")).isEqualTo(errors1);
            assertThat(dump(c, "SELECT key, value FROM meta WHERE key <> 'indexed_at' ORDER BY key"))
                    .isEqualTo(meta1);
        }
    }

    @Test
    void removedFileDisappearsOnNextFullRun() throws Exception {
        createProject();
        run(false);
        Files.delete(tmp.resolve("legacy.txt"));
        run(false);
        try (Connection c = connect()) {
            assertThat(dump(c, "SELECT path FROM files ORDER BY path")).containsExactly(
                    List.of("README.md"), List.of("src/main/java/ru/mcs/Main.java"));
            assertThat(dump(c, "SELECT COUNT(*) FROM index_errors WHERE stage = 'READ'"))
                    .containsExactly(List.of(0));
        }
    }

    @Test
    void finalDatabaseStateIsSingleConsistentFile() throws Exception {
        createProject();
        run(false);
        Path file = db();
        assertThat(file).exists();
        assertThat(file.resolveSibling("index.db-wal")).doesNotExist();
        assertThat(file.resolveSibling("index.db-shm")).doesNotExist();
        try (Connection c = connect()) {
            assertThat(dump(c, "PRAGMA integrity_check")).containsExactly(List.of("ok"));
            assertThat(dump(c, "PRAGMA foreign_key_check")).isEmpty();
        }
    }
}