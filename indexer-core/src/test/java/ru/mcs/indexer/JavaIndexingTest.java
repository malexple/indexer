package ru.mcs.indexer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.mcs.indexer.files.GitRunner;
import ru.mcs.indexer.testing.DemoSources;
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
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JavaIndexingTest {

    private static final String MANAGER_PATH = "src/main/java/demo/core/Manager.java";
    private static final List<String> TABLES = List.of(
            "files", "types", "members", "annotations", "imports", "type_relations", "index_errors");

    @TempDir
    Path tmp;

    private void createProject() throws IOException {
        Path manager = tmp.resolve(MANAGER_PATH);
        Files.createDirectories(manager.getParent());
        Files.writeString(manager, DemoSources.MANAGER, StandardCharsets.UTF_8);
        Files.writeString(manager.resolveSibling("Broken.java"), "package demo.core;\nclass {\n",
                StandardCharsets.UTF_8);
    }

    private void run(boolean noSource) throws IOException {
        IndexerOptions options = new IndexerOptions(tmp, tmp.resolve(".index/index.db"), Set.of("java"),
                IndexerOptions.DEFAULT_EXTENSIONS, List.of(), 500, false, noSource, false, false, false);
        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        IndexerApp.run(options, new Log(false, sink, sink), GitRunner.disabled());
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + tmp.resolve(".index/index.db"));
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

    private static long count(Connection c, String sql) throws SQLException {
        return ((Number) dump(c, sql).get(0).get(0)).longValue();
    }

    @Test
    void writesAstTablesForManager() throws Exception {
        createProject();
        run(false);
        String fileFilter = " JOIN files f ON f.id = x.file_id WHERE f.path = '" + MANAGER_PATH + "'";
        try (Connection c = connect()) {
            assertThat(count(c, "SELECT COUNT(*) FROM types x" + fileFilter)).isEqualTo(6);
            assertThat(count(c, "SELECT COUNT(*) FROM members x" + fileFilter)).isEqualTo(14);
            assertThat(count(c, "SELECT COUNT(*) FROM annotations x" + fileFilter)).isEqualTo(3);
            assertThat(count(c, "SELECT COUNT(*) FROM imports x" + fileFilter)).isEqualTo(3);
            assertThat(count(c, "SELECT COUNT(*) FROM type_relations r JOIN types x ON x.id = r.type_id"
                    + fileFilter)).isEqualTo(7);

            assertThat(dump(c, "SELECT m.signature FROM members m JOIN types t ON t.id = m.type_id "
                    + "WHERE t.fqn = 'demo.core.Manager' AND m.kind = 'METHOD' ORDER BY m.line_from"))
                    .containsExactly(List.of("run():void"), List.of("open(Map.Entry,int[],boolean):R"));

            assertThat(dump(c, "SELECT a.owner_kind, t.fqn, a.args FROM annotations a "
                    + "JOIN types t ON t.id = a.owner_id WHERE a.owner_kind = 'TYPE' AND a.name = 'Deprecated' "
                    + "AND t.fqn = 'demo.core.Manager'"))
                    .containsExactly(List.of("TYPE", "demo.core.Manager", "since = \"1\""));

            List<List<Object>> run = dump(c, "SELECT f.content, m.line_from, m.line_to FROM members m "
                    + "JOIN files f ON f.id = m.file_id JOIN types t ON t.id = m.type_id "
                    + "WHERE t.fqn = 'demo.core.Manager' AND m.name = 'run'");
            String[] lines = ((String) run.get(0).get(0)).split("\n", -1);
            int from = ((Number) run.get(0).get(1)).intValue();
            int to = ((Number) run.get(0).get(2)).intValue();
            assertThat(String.join("\n", Arrays.copyOfRange(lines, from - 1, to))).isEqualTo(
                    "    /** Opens it. */\n    @Override\n    public void run() {}");

            assertThat(dump(c, "SELECT e.stage, f.path, e.file_id IS NOT NULL FROM index_errors e "
                    + "JOIN files f ON f.id = e.file_id WHERE e.stage = 'PARSE'"))
                    .containsExactly(List.of("PARSE", "src/main/java/demo/core/Broken.java", 1));
            assertThat(dump(c, "PRAGMA foreign_key_check")).isEmpty();
            assertThat(dump(c, "PRAGMA integrity_check")).containsExactly(List.of("ok"));
        }
    }

    @Test
    void secondRunProducesIdenticalTables() throws Exception {
        createProject();
        run(false);
        List<List<List<Object>>> first = new ArrayList<>();
        try (Connection c = connect()) {
            for (String table : TABLES) {
                first.add(dump(c, "SELECT * FROM " + table + " ORDER BY id"));
            }
        }
        run(false);
        try (Connection c = connect()) {
            for (int i = 0; i < TABLES.size(); i++) {
                assertThat(dump(c, "SELECT * FROM " + TABLES.get(i) + " ORDER BY id"))
                        .as(TABLES.get(i)).isEqualTo(first.get(i));
            }
        }
    }

    @Test
    void noSourceStillIndexesAst() throws Exception {
        createProject();
        run(true);
        try (Connection c = connect()) {
            assertThat(count(c, "SELECT COUNT(*) FROM files WHERE content IS NOT NULL")).isZero();
            assertThat(count(c, "SELECT COUNT(*) FROM types t JOIN files f ON f.id = t.file_id "
                    + "WHERE f.path = '" + MANAGER_PATH + "'")).isEqualTo(6);
        }
    }
}