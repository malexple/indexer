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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IncrementalIndexingTest {

    private static final String FTS_SQL = "SELECT f.path FROM code_fts JOIN files f ON f.id = code_fts.rowid "
            + "WHERE code_fts MATCH ? ORDER BY f.path";

    private static final List<String> TABLES = List.of("files", "types", "members", "calls", "type_deps",
            "type_relations", "annotations", "imports", "config_entries", "gradle_modules", "gradle_deps",
            "index_errors");

    @TempDir
    Path tmp;

    private void write(String rel, String content) throws IOException {
        Path file = tmp.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private void createProject() throws IOException {
        write("src/main/java/demo/A.java", "package demo;\npublic class A {\n    public void foo() {}\n}\n");
        write("src/main/java/demo/B.java", "package demo;\npublic class B {\n    void bar(A a) { a.foo(); }\n}\n");
        write("README.md", "# Title\nalphaToken\n");
        write("config.yml", "key: value\n");
        write("settings.gradle", "rootProject.name = 'demo'\n");
        write("build.gradle", "dependencies {\n    implementation 'g:n:1'\n}\n");
    }

    private String run(boolean incremental, boolean noSource) throws IOException {
        IndexerOptions options = new IndexerOptions(tmp, tmp.resolve(".index/index.db"), Set.of("java"),
                IndexerOptions.DEFAULT_EXTENSIONS, List.of(), 500, incremental, noSource, true, false, false);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream stream = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        try {
            IndexerApp.run(options, new Log(false, stream, stream), GitRunner.disabled());
        } finally {
            stream.flush();
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private String run(boolean incremental) throws IOException {
        return run(incremental, false);
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + tmp.resolve(".index/index.db"));
    }

    private static List<String> rows(Connection c, String sql, String... params) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    StringBuilder row = new StringBuilder();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        if (i > 1) {
                            row.append('|');
                        }
                        row.append(rs.getString(i));
                    }
                    rows.add(row.toString());
                }
            }
        }
        return rows;
    }

    private static List<List<String>> snapshot(Connection c) throws SQLException {
        List<List<String>> snapshot = new ArrayList<>();
        for (String table : TABLES) {
            snapshot.add(rows(c, "SELECT * FROM " + table + " ORDER BY id"));
        }
        return snapshot;
    }

    /** FTS5 integrity-check with rank=1 also compares the index with the external content table. */
    private static void assertFtsConsistent(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("INSERT INTO code_fts(code_fts, rank) VALUES ('integrity-check', 1)");
        }
        assertThat(rows(c, "PRAGMA integrity_check")).containsExactly("ok");
        assertThat(rows(c, "PRAGMA foreign_key_check")).isEmpty();
    }

    @Test
    void noOpIncrementalRunChangesNothing() throws Exception {
        createProject();
        assertThat(run(false)).contains("mode=full");
        List<List<String>> before;
        List<String> metaBefore;
        try (Connection c = connect()) {
            before = snapshot(c);
            metaBefore = rows(c, "SELECT key, value FROM meta WHERE key NOT IN ('indexed_at', 'notes') ORDER BY key");
        }
        String log = run(true);
        assertThat(log).contains("mode=incremental", "written=0", "unchanged=6", "removed=0");
        try (Connection c = connect()) {
            assertThat(snapshot(c)).isEqualTo(before);
            assertThat(rows(c, "SELECT key, value FROM meta WHERE key NOT IN ('indexed_at', 'notes') ORDER BY key"))
                    .isEqualTo(metaBefore);
            assertFtsConsistent(c);
        }
        assertThat(tmp.resolve(".index/index.db-wal")).doesNotExist();
        assertThat(tmp.resolve(".index/index.db-shm")).doesNotExist();
    }

    @Test
    void changedFilesAreReplacedAndFtsFindsOnlyNewText() throws Exception {
        createProject();
        run(false);
        String untouchedBefore;
        try (Connection c = connect()) {
            assertThat(rows(c, FTS_SQL, "\"alphaToken\"")).containsExactly("README.md");
            untouchedBefore = rows(c, "SELECT id, indexed_at FROM files WHERE path = 'src/main/java/demo/B.java'").get(0);
        }
        write("README.md", "# Title\nbetaToken\n");
        write("src/main/java/demo/A.java",
                "package demo;\npublic class A {\n    public void foo() {}\n    public int baz() { return 1; }\n}\n");

        String log = run(true);

        assertThat(log).contains("mode=incremental", "written=2", "unchanged=4");
        try (Connection c = connect()) {
            assertThat(rows(c, FTS_SQL, "\"betaToken\"")).containsExactly("README.md");
            assertThat(rows(c, FTS_SQL, "\"alphaToken\"")).isEmpty();
            assertThat(rows(c, "SELECT m.signature FROM members m JOIN types t ON t.id = m.type_id "
                    + "WHERE t.fqn = 'demo.A' AND m.kind = 'METHOD' ORDER BY m.line_from"))
                    .containsExactly("foo():void", "baz():int");
            assertThat(rows(c, "SELECT id, indexed_at FROM files WHERE path = 'src/main/java/demo/B.java'"))
                    .containsExactly(untouchedBefore);
            assertThat(rows(c, "SELECT COUNT(*) FROM types WHERE fqn = 'demo.A'")).containsExactly("1");
            assertThat(rows(c, "SELECT value FROM meta WHERE key = 'changes_since_full'")).containsExactly("2");
            assertFtsConsistent(c);
        }
    }

    @Test
    void deletedFileDisappearsWithAllItsData() throws Exception {
        createProject();
        run(false);
        try (Connection c = connect()) {
            assertThat(rows(c, FTS_SQL, "\"bar\"")).containsExactly("src/main/java/demo/B.java");
            assertThat(rows(c, "SELECT COUNT(*) FROM calls c JOIN files f ON f.id = c.file_id "
                    + "WHERE f.path LIKE '%B.java'")).isNotEqualTo(List.of("0"));
        }
        Files.delete(tmp.resolve("src/main/java/demo/B.java"));

        String log = run(true);

        assertThat(log).contains("mode=incremental", "removed=1", "written=0");
        try (Connection c = connect()) {
            assertThat(rows(c, "SELECT COUNT(*) FROM files WHERE path LIKE '%B.java'")).containsExactly("0");
            assertThat(rows(c, "SELECT COUNT(*) FROM types WHERE fqn = 'demo.B'")).containsExactly("0");
            assertThat(rows(c, "SELECT COUNT(*) FROM members WHERE name = 'bar'")).containsExactly("0");
            assertThat(rows(c, "SELECT COUNT(*) FROM calls WHERE callee_name = 'foo'")).containsExactly("0");
            assertThat(rows(c, FTS_SQL, "\"bar\"")).isEmpty();
            assertFtsConsistent(c);
        }
    }

    @Test
    void newFileIsAdded() throws Exception {
        createProject();
        run(false);
        write("src/main/java/demo/C.java", "package demo;\npublic class C {}\n");

        String log = run(true);

        assertThat(log).contains("written=1", "unchanged=6");
        try (Connection c = connect()) {
            assertThat(rows(c, "SELECT fqn FROM types ORDER BY fqn")).containsExactly("demo.A", "demo.B", "demo.C");
            assertThat(rows(c, "SELECT module FROM files WHERE path LIKE '%C.java'")).containsExactly("demo");
            assertFtsConsistent(c);
        }
    }

    @Test
    void gradleModelIsRebuiltFromUnchangedScripts() throws Exception {
        createProject();
        run(false);
        write("build.gradle", "dependencies {\n    implementation 'g:n:2'\n}\n");

        String log = run(true);

        assertThat(log).contains("written=1");
        try (Connection c = connect()) {
            assertThat(rows(c, "SELECT name, dir, build_file FROM gradle_modules"))
                    .containsExactly("demo|.|build.gradle");
            assertThat(rows(c, "SELECT config, dep_kind, target FROM gradle_deps"))
                    .containsExactly("implementation|EXTERNAL|g:n:2");
            assertThat(rows(c, "SELECT module FROM files WHERE path = 'src/main/java/demo/A.java'"))
                    .containsExactly("demo");
        }
    }

    @Test
    void noSourceIncrementalKeepsFtsConsistent() throws Exception {
        createProject();
        run(false, true);
        write("README.md", "# Title\nbetaToken\n");

        String log = run(true, true);

        assertThat(log).contains("mode=incremental", "written=1");
        try (Connection c = connect()) {
            assertThat(rows(c, "SELECT COUNT(*) FROM files WHERE content IS NOT NULL")).containsExactly("0");
            assertThat(rows(c, FTS_SQL, "\"betaToken\"")).isEmpty();
            assertThat(rows(c, FTS_SQL, "\"readme\"")).containsExactly("README.md");
            assertFtsConsistent(c);
        }
    }

    @Test
    void incompatibleOptionsForceFullRun() throws Exception {
        createProject();
        run(false, false);

        String log = run(true, true);

        assertThat(log).contains("incremental run is not possible", "source_included changed", "mode=full");
        try (Connection c = connect()) {
            assertThat(rows(c, "SELECT COUNT(*) FROM files WHERE content IS NOT NULL")).containsExactly("0");
            assertFtsConsistent(c);
        }
    }

    @Test
    void missingOrUnfinishedPreviousIndexForcesFullRun() throws Exception {
        createProject();
        assertThat(run(true)).contains("no previous index", "mode=full");

        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("UPDATE meta SET value = 'indexing in progress' WHERE key = 'notes'");
        }
        assertThat(run(true)).contains("did not finish", "mode=full");
        try (Connection c = connect()) {
            assertThat(rows(c, "SELECT value FROM meta WHERE key = 'notes'")).containsExactly("");
        }
    }

    @Test
    void notesExplainStaleResolutionAfterIncrementalRun() throws Exception {
        createProject();
        run(false);
        write("README.md", "# Title\nbetaToken\n");
        run(true);
        try (Connection c = connect()) {
            String notes = rows(c, "SELECT value FROM meta WHERE key = 'notes'").get(0);
            assertThat(notes).contains("not re-resolved", "Changes since the last full run: 1");
        }
    }
}