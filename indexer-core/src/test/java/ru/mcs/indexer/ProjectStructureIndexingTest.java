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

class ProjectStructureIndexingTest {

    private static final List<String> TABLES = List.of("files", "gradle_modules", "gradle_deps", "java_modules",
            "module_directives", "config_entries", "types", "index_errors");

    @TempDir
    Path tmp;

    private void write(String rel, String content) throws IOException {
        Path file = tmp.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private void createProject() throws IOException {
        write("settings.gradle", "rootProject.name = 'demo'\ninclude ':core', ':plugins:hello'\n");
        write("build.gradle", "plugins { id 'java' }\n");
        write("core/build.gradle", "dependencies {\n    implementation 'com.acme:lib:1.0'\n}\n");
        write("plugins/hello/build.gradle", "dependencies {\n    compileOnly project(':core')\n}\n");
        write("core/src/main/java/module-info.java",
                "module demo.core {\n    exports demo.core;\n    provides demo.core.Svc with demo.core.Impl;\n}\n");
        write("core/src/main/java/demo/core/Svc.java", "package demo.core;\npublic interface Svc {}\n");
        write("core/src/main/java/demo/core/Impl.java", "package demo.core;\npublic class Impl implements Svc {}\n");
        write("plugins/hello/src/main/resources/plugin.yml",
                "id: hello\ncontributions:\n  actions:\n    - id: hello.say\n");
        write("core/src/main/resources/messages_ru.properties", "greeting=\\u041f\\u0440\\u0438\\u0432\\u0435\\u0442\n");
        write("README.md", "# demo\n");
    }

    private void run() throws IOException {
        IndexerOptions options = new IndexerOptions(tmp, tmp.resolve(".index/index.db"), Set.of("java"),
                IndexerOptions.DEFAULT_EXTENSIONS, List.of(), 500, false, false, false, false, false);
        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        IndexerApp.run(options, new Log(false, sink, sink), GitRunner.disabled());
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + tmp.resolve(".index/index.db"));
    }

    private static List<String> rows(Connection c, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
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
        return rows;
    }

    @Test
    void indexesModulesDependenciesAndConfigs() throws Exception {
        createProject();
        run();
        try (Connection c = connect()) {
            assertThat(rows(c, "SELECT name, dir, build_file FROM gradle_modules ORDER BY id")).containsExactly(
                    "demo|.|build.gradle",
                    "core|core|core/build.gradle",
                    "plugins/hello|plugins/hello|plugins/hello/build.gradle");
            assertThat(rows(c, "SELECT m.name, d.config, d.dep_kind, d.target FROM gradle_deps d "
                    + "JOIN gradle_modules m ON m.id = d.module_id ORDER BY d.id")).containsExactly(
                    "core|implementation|EXTERNAL|com.acme:lib:1.0",
                    "plugins/hello|compileOnly|PROJECT|:core");
            assertThat(rows(c, "SELECT path, module FROM files WHERE path IN ('README.md', "
                    + "'core/src/main/java/demo/core/Svc.java', 'plugins/hello/src/main/resources/plugin.yml') "
                    + "ORDER BY path")).containsExactly(
                    "README.md|demo",
                    "core/src/main/java/demo/core/Svc.java|core",
                    "plugins/hello/src/main/resources/plugin.yml|plugins/hello");

            assertThat(rows(c, "SELECT m.name, f.path, m.is_open FROM java_modules m JOIN files f ON f.id = m.file_id"))
                    .containsExactly("demo.core|core/src/main/java/module-info.java|0");
            assertThat(rows(c, "SELECT directive, target, extra FROM module_directives ORDER BY id")).containsExactly(
                    "exports|demo.core|null", "provides|demo.core.Svc|with demo.core.Impl");

            assertThat(rows(c, "SELECT c.key_path, c.value, c.line FROM config_entries c JOIN files f ON f.id = c.file_id "
                    + "WHERE f.path LIKE '%plugin.yml' ORDER BY c.id")).containsExactly(
                    "id|hello|1", "contributions.actions[0].id|hello.say|4");
            assertThat(rows(c, "SELECT c.key_path, c.value, c.line FROM config_entries c JOIN files f ON f.id = c.file_id "
                    + "WHERE f.path LIKE '%messages_ru.properties'")).containsExactly("greeting|Привет|1");

            assertThat(rows(c, "PRAGMA foreign_key_check")).isEmpty();
            assertThat(rows(c, "PRAGMA integrity_check")).containsExactly("ok");
            assertThat(rows(c, "SELECT COUNT(*) FROM index_errors")).containsExactly("0");
        }
    }

    @Test
    void secondRunProducesIdenticalTables() throws Exception {
        createProject();
        run();
        List<List<String>> first = new ArrayList<>();
        try (Connection c = connect()) {
            for (String table : TABLES) {
                first.add(rows(c, "SELECT * FROM " + table + " ORDER BY id"));
            }
        }
        run();
        try (Connection c = connect()) {
            for (int i = 0; i < TABLES.size(); i++) {
                assertThat(rows(c, "SELECT * FROM " + TABLES.get(i) + " ORDER BY id"))
                        .as(TABLES.get(i)).isEqualTo(first.get(i));
            }
        }
    }
}