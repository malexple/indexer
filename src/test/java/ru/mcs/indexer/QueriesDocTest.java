package ru.mcs.indexer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class QueriesDocTest {

    @TempDir
    Path tmp;

    private static String document() throws IOException {
        try (InputStream in = QueriesDocTest.class.getResourceAsStream("/QUERIES.md")) {
            assertThat(in).as("QUERIES.md must be bundled as a resource").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    private void createProject() throws IOException {
        Path source = tmp.resolve("src/main/java/demo/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package demo;\npublic class Main { void run() { helper(); } void helper() {} }\n");
        Files.writeString(tmp.resolve("README.md"), "# Demo\nsee ru.mcs.Main\n");
        Files.writeString(tmp.resolve("plugin.yml"), "contributions:\n  actions:\n    - id: a\n");
    }

    @Test
    void documentHasAllSixteenSectionsAndTheAssistantRules() throws IOException {
        String text = document();
        Matcher headings = Pattern.compile("(?m)^## (\\d+)\\.").matcher(text);
        List<Integer> numbers = new ArrayList<>();
        while (headings.find()) {
            numbers.add(Integer.parseInt(headings.group(1)));
        }
        assertThat(numbers).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16);
        assertThat(text).contains(
                "first `meta` and `projects`, then find the type",
                "double quotes",
                "lines[line_from - 1:line_to]",
                "## Schema in short",
                "## 16. Several projects in one database");
    }

    @Test
    void everySqlBlockExecutesOnARealIndex() throws Exception {
        createProject();
        int code = ru.mcs.indexer.IndexerMain.run(new String[]{"-r", tmp.toString(), "-l=java", "--no-resolve"});
        assertThat(code).isZero();

        Matcher blocks = Pattern.compile("(?s)```sql\n(.*?)```").matcher(document());
        int executed = 0;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + tmp.resolve(".index/index.db"))) {
            while (blocks.find()) {
                String sql = blocks.group(1).strip();
                try (Statement st = c.createStatement()) {
                    boolean hasResult = st.execute(sql);
                    if (hasResult) {
                        try (ResultSet rs = st.getResultSet()) {
                            while (rs.next()) {
                                rs.getString(1);
                            }
                        }
                    }
                } catch (SQLException e) {
                    throw new AssertionError("QUERIES.md block #" + (executed + 1) + " failed: " + e.getMessage()
                            + "\n" + sql, e);
                }
                executed++;
            }
        }
        assertThat(executed).isGreaterThanOrEqualTo(15);
    }

    @Test
    void queriesFileIsCopiedNextToTheDatabase() throws Exception {
        createProject();
        ru.mcs.indexer.IndexerMain.run(new String[]{"-r", tmp.toString(), "-l=java", "--no-resolve"});
        Path copy = tmp.resolve(".index/QUERIES.md");
        assertThat(copy).exists();
        assertThat(Files.readString(copy).replace("\r\n", "\n")).isEqualTo(document());

        long before = Files.getLastModifiedTime(copy).toMillis();
        ru.mcs.indexer.IndexerMain.run(new String[]{"-r", tmp.toString(), "-l=java", "--no-resolve"});
        assertThat(Files.getLastModifiedTime(copy).toMillis()).isEqualTo(before);
    }
}