package ru.mcs.mcpsql;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.mcs.indexer.IndexerApp;
import ru.mcs.indexer.IndexerOptions;
import ru.mcs.indexer.files.GitRunner;
import ru.mcs.indexer.patch.PatchApplier;
import ru.mcs.indexer.util.Log;
import ru.mcs.mcpsql.config.ServiceProperties;
import ru.mcs.mcpsql.query.IndexQueries;
import ru.mcs.mcpsql.workspace.WorkspaceService;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceFlowTest {

    @TempDir
    Path tmp;

    private WorkspaceService workspaces;
    private IndexQueries queries;

    @BeforeEach
    void setUp() throws IOException {
        ServiceProperties properties = new ServiceProperties(tmp.resolve("data"), "r", "w", 8000, 3, 200, 50);
        workspaces = new WorkspaceService(properties);
        queries = new IndexQueries(workspaces, properties);
    }

    private void write(Path root, String rel, String content) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private Path createProject() throws IOException {
        Path root = tmp.resolve("demo");
        write(root, "src/main/java/demo/A.java", "package demo;\npublic class A {\n    public void foo() {}\n}\n");
        write(root, "src/main/java/demo/B.java", "package demo;\npublic class B {\n    void bar(A a) { a.foo(); }\n}\n");
        write(root, "README.md", "# demo\nalphaToken\n");
        return root;
    }

    private void index(Path root, Path out, boolean incremental, Path patchDir) throws IOException {
        IndexerOptions options = new IndexerOptions(root, out, Set.of("java"), IndexerOptions.DEFAULT_EXTENSIONS,
                List.of(), 500, incremental, false, false, false, false, "demo", patchDir);
        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        IndexerApp.run(options, new Log(false, sink, sink), GitRunner.disabled());
    }

    private void upload(Path database) throws Exception {
        try (InputStream in = Files.newInputStream(database)) {
            workspaces.putDatabase("ws", in);
        }
    }

    @Test
    void toolsAnswerFromAnUploadedIndex() throws Exception {
        Path client = tmp.resolve("client/index.db");
        index(createProject(), client, false, null);
        upload(client);

        assertThat(queries.indexInfo(null, true)).contains("workspace: ws", "seq: 1", "demo", "tables:");
        assertThat(queries.findType("A", null, null)).contains("demo.A");
        assertThat(queries.context("demo.A", null, null)).contains("type: demo.A", "foo():void");
        assertThat(queries.readSource("demo.A", "foo(", null, null)).contains("public void foo()");
        assertThat(queries.readLines("src/main/java/demo/A.java", 1, 2, null, null)).contains("package demo;");
        assertThat(queries.callers("foo", null, null, null)).contains("demo.B");
        assertThat(queries.search("alphaToken", null, 5, null)).contains("README.md");
        assertThat(queries.sql("select count(*) as n from files", null)).contains("n\n");
    }

    @Test
    void patchUploadMovesTheWorkspaceForward() throws Exception {
        Path root = createProject();
        Path client = tmp.resolve("client/index.db");
        index(root, client, false, null);
        upload(client);

        write(root, "src/main/java/demo/C.java", "package demo;\npublic class C {}\n");
        Path patches = tmp.resolve("patches");
        index(root, client, true, patches);
        Path patch = patches.resolve("patch-1-2.sql.gz");

        PatchApplier.Result result;
        try (InputStream in = Files.newInputStream(patch)) {
            result = workspaces.applyPatch("ws", in);
        }
        assertThat(result.toSeq()).isEqualTo(2);
        assertThat(queries.findType("C", null, "ws")).contains("demo.C");

        assertThatThrownBy(() -> {
            try (InputStream in = Files.newInputStream(patch)) {
                workspaces.applyPatch("ws", in);
            }
        }).isInstanceOf(IllegalStateException.class);
        assertThat(workspaces.info("ws").seq()).isEqualTo(2);
    }

    @Test
    void sqlToolRefusesWrites() throws Exception {
        assertThatThrownBy(() -> queries.sql("delete from files", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void uploadOfAForeignFileIsRejectedAndLeavesNoWorkspace() throws Exception {
        assertThatThrownBy(() -> workspaces.putDatabase("junk",
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(workspaces.list()).isEmpty();
    }
}