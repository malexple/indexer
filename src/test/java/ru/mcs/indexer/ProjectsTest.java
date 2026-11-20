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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectsTest {

    private static final String BETA_SNAPSHOT = "SELECT f.id, f.sha256, f.indexed_at, "
            + "(SELECT COUNT(*) FROM types t WHERE t.file_id = f.id), "
            + "(SELECT MIN(t.id) FROM types t WHERE t.file_id = f.id) "
            + "FROM files f WHERE f.project_id = 'beta' ORDER BY f.id";

    @TempDir
    Path tmp;

    private Path db() {
        return tmp.resolve("ws/index.db");
    }

    private void write(Path root, String rel, String content) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private Path createProject(String name, String readme) throws IOException {
        Path root = tmp.resolve(name);
        write(root, "src/main/java/demo/A.java", "package demo;\npublic class A {\n    public void foo() {}\n}\n");
        write(root, "README.md", readme);
        return root;
    }

    private void run(Path root, String project, boolean incremental) throws IOException {
        IndexerOptions options = new IndexerOptions(root, db(), Set.of("java"),
                IndexerOptions.DEFAULT_EXTENSIONS, List.of(), 500, incremental, false, true, false, false, project);
        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        IndexerApp.run(options, new Log(false, sink, sink), GitRunner.disabled());
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + db());
    }

    private static List<String> rows(Connection c, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= columns; i++) {
                    if (i > 1) {
                        row.append('|');
                    }
                    row.append(rs.getString(i));
                }
                rows.add(row.toString());
            }
        }
        return rows;
    }

    private static String seq(Connection c) throws SQLException {
        return rows(c, "SELECT value FROM meta WHERE scope = '' AND key = 'seq'").get(0);
    }

    @Test
    void projectsShareOneDatabaseWithoutInterference() throws Exception {
        Path alpha = createProject("alpha-src", "# alpha\n");
        Path beta = createProject("beta-src", "# beta\n");
        run(alpha, "alpha", false);
        run(beta, "beta", false);

        try (Connection c = connect()) {
            assertThat(rows(c, "SELECT slug FROM projects ORDER BY slug")).containsExactly("alpha", "beta");
            assertThat(rows(c, "SELECT project_id, COUNT(*) FROM files GROUP BY project_id ORDER BY 1"))
                    .containsExactly("alpha|2", "beta|2");
            assertThat(rows(c, "SELECT COUNT(*) FROM types WHERE fqn = 'demo.A'")).containsExactly("2");
            assertThat(rows(c, "SELECT value FROM meta WHERE scope = 'alpha' AND key = 'file_count'"))
                    .containsExactly("2");
            assertThat(seq(c)).isEqualTo("2");
            assertThat(rows(c, "SELECT COUNT(*) FROM meta WHERE scope = '' AND key = 'db_id'"))
                    .containsExactly("1");
            assertThat(rows(c, "PRAGMA foreign_key_check")).isEmpty();
        }
    }

    @Test
    void incrementalRunTouchesOnlyItsProjectAndNoOpKeepsSeq() throws Exception {
        Path alpha = createProject("alpha-src", "# alpha\n");
        Path beta = createProject("beta-src", "# beta\n");
        run(alpha, "alpha", false);
        run(beta, "beta", false);
        List<String> betaBefore;
        try (Connection c = connect()) {
            betaBefore = rows(c, BETA_SNAPSHOT);
        }

        write(alpha, "README.md", "# alpha changed\n");
        run(alpha, "alpha", true);
        try (Connection c = connect()) {
            assertThat(seq(c)).isEqualTo("3");
            assertThat(rows(c, BETA_SNAPSHOT)).isEqualTo(betaBefore);
        }

        run(alpha, "alpha", true);
        try (Connection c = connect()) {
            assertThat(seq(c)).isEqualTo("3");
        }
    }

    @Test
    void fullRunOfOneProjectKeepsTheOther() throws Exception {
        Path alpha = createProject("alpha-src", "# alpha\n");
        Path beta = createProject("beta-src", "# beta\n");
        run(alpha, "alpha", false);
        run(beta, "beta", false);
        List<String> betaBefore;
        try (Connection c = connect()) {
            betaBefore = rows(c, BETA_SNAPSHOT);
        }

        run(alpha, "alpha", false);
        try (Connection c = connect()) {
            assertThat(rows(c, BETA_SNAPSHOT)).isEqualTo(betaBefore);
            assertThat(rows(c, "SELECT project_id, COUNT(*) FROM files GROUP BY project_id ORDER BY 1"))
                    .containsExactly("alpha|2", "beta|2");
            assertThat(rows(c, "SELECT COUNT(*) FROM types WHERE fqn = 'demo.A'")).containsExactly("2");
            assertThat(rows(c, "PRAGMA foreign_key_check")).isEmpty();
        }
    }
}