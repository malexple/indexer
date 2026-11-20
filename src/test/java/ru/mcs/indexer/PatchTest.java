package ru.mcs.indexer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.mcs.indexer.files.GitRunner;
import ru.mcs.indexer.patch.PatchApplier;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatchTest {

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

    private Path createProject(String name, String token) throws IOException {
        Path root = tmp.resolve(name);
        write(root, "src/main/java/demo/A.java", "package demo;\npublic class A {\n    public void foo() {}\n}\n");
        write(root, "src/main/java/demo/B.java", "package demo;\npublic class B {\n    void bar(A a) { a.foo(); }\n}\n");
        write(root, "README.md", "# " + name + "\n" + token + "\n");
        write(root, "settings.gradle", "rootProject.name = '" + name + "'\n");
        write(root, "build.gradle", "dependencies {\n    implementation 'g:n:1'\n}\n");
        return root;
    }

    private void run(Path root, String project, boolean incremental, Path patchDir) throws IOException {
        IndexerOptions options = new IndexerOptions(root, db(), Set.of("java"), IndexerOptions.DEFAULT_EXTENSIONS,
                List.of(), 500, incremental, false, true, false, false, project, patchDir);
        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        IndexerApp.run(options, new Log(false, sink, sink), GitRunner.disabled());
    }

    private static List<String> dump(Path file) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement st = c.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' "
                    + "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'code_fts%' ORDER BY name")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            for (String table : tables) {
                try (ResultSet rs = st.executeQuery("SELECT * FROM " + table + " ORDER BY 1, 2")) {
                    int columns = rs.getMetaData().getColumnCount();
                    while (rs.next()) {
                        StringBuilder row = new StringBuilder(table);
                        for (int i = 1; i <= columns; i++) {
                            row.append('|').append(rs.getString(i));
                        }
                        rows.add(row.toString());
                    }
                }
            }
            for (String token : List.of("alphaToken", "betaToken", "gammaToken")) {
                try (ResultSet rs = st.executeQuery("SELECT rowid FROM code_fts WHERE code_fts MATCH '\""
                        + token + "\"' ORDER BY rowid")) {
                    while (rs.next()) {
                        rows.add("fts " + token + " " + rs.getLong(1));
                    }
                }
            }
        }
        return rows;
    }

    @Test
    void patchTurnsTheOldDatabaseIntoTheNewOne() throws Exception {
        Path root = createProject("p", "alphaToken");
        run(root, "p", false, null);
        Path base = tmp.resolve("base.db");
        Files.copy(db(), base);

        write(root, "README.md", "# p\nbetaToken\n");
        write(root, "src/main/java/demo/A.java",
                "package demo;\npublic class A {\n    public void foo() {}\n    public int baz() { return 1; }\n}\n");
        Files.delete(root.resolve("src/main/java/demo/B.java"));
        write(root, "src/main/java/demo/C.java", "package demo;\npublic class C { gammaToken }\n");
        write(root, "build.gradle", "dependencies {\n    implementation 'g:n:2'\n}\n");
        Path patches = tmp.resolve("patches");
        run(root, "p", true, patches);

        Path patch = patches.resolve("patch-1-2.sql.gz");
        assertThat(patch).exists();
        Path applied = tmp.resolve("applied.db");
        Files.copy(base, applied);
        PatchApplier.Result result = PatchApplier.apply(applied, patch);

        assertThat(result.fromSeq()).isEqualTo(1);
        assertThat(result.toSeq()).isEqualTo(2);
        assertThat(dump(applied)).isEqualTo(dump(db()));
    }

    @Test
    void secondApplicationAndWrongBaseAreRefused() throws Exception {
        Path root = createProject("p", "alphaToken");
        run(root, "p", false, null);
        Path base = tmp.resolve("base.db");
        Files.copy(db(), base);
        write(root, "README.md", "# p\nbetaToken\n");
        Path patches = tmp.resolve("patches");
        run(root, "p", true, patches);
        Path patch = patches.resolve("patch-1-2.sql.gz");

        Path applied = tmp.resolve("applied.db");
        Files.copy(base, applied);
        PatchApplier.apply(applied, patch);
        List<String> afterFirst = dump(applied);
        assertThatThrownBy(() -> PatchApplier.apply(applied, patch))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("seq");
        assertThat(dump(applied)).isEqualTo(afterFirst);
    }

    @Test
    void noChangesProduceNoPatch() throws Exception {
        Path root = createProject("p", "alphaToken");
        run(root, "p", false, null);
        Path patches = tmp.resolve("patches");
        run(root, "p", true, patches);
        assertThat(patches).doesNotExist();
    }

    @Test
    void addingAProjectIsAPatchToo() throws Exception {
        Path alpha = createProject("alpha-src", "alphaToken");
        Path beta = createProject("beta-src", "betaToken");
        run(alpha, "alpha", false, null);
        Path base = tmp.resolve("base.db");
        Files.copy(db(), base);

        Path patches = tmp.resolve("patches");
        run(beta, "beta", false, patches);

        Path patch = patches.resolve("patch-1-2.sql.gz");
        assertThat(patch).exists();
        Path applied = tmp.resolve("applied.db");
        Files.copy(base, applied);
        PatchApplier.apply(applied, patch);
        assertThat(dump(applied)).isEqualTo(dump(db()));
    }
}