package ru.mcs.indexer.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.mcs.indexer.IndexerOptions;
import ru.mcs.indexer.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FileSelectorTest {

    @TempDir
    Path tmp;

    private static Log quietLog() {
        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        return new Log(false, sink, sink);
    }

    private IndexerOptions options(int maxKb, List<String> excludes) {
        return new IndexerOptions(tmp, tmp.resolve(".index/index.db"), Set.of("java"),
                IndexerOptions.DEFAULT_EXTENSIONS, excludes, maxKb,
                false, false, false, false, false);
    }

    private SelectionResult selectWithoutGit(int maxKb, List<String> excludes) throws IOException {
        return new FileSelector(options(maxKb, excludes), quietLog(), GitRunner.disabled()).select();
    }

    private void write(String rel, String content) throws IOException {
        Path file = tmp.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private void writeBytes(String rel, byte[] content) throws IOException {
        Path file = tmp.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.write(file, content);
    }

    private static List<String> paths(SelectionResult result) {
        return result.files().stream().map(SelectedFile::path).toList();
    }

    private void createGitignoreProject() throws IOException {
        write(".gitignore", "*.md\n/generated/\n!keep.md\n");
        write("README.md", "# readme\n");
        write("docs/keep.md", "# keep\n");
        write("generated/Gen.java", "class Gen {}\n");
        write("src/Main.java", "class Main {}\n");
        write("sub/.gitignore", "local.properties\n");
        write("sub/local.properties", "a=1\n");
        write("sub/app.properties", "a=2\n");
    }

    private static final List<String> GITIGNORE_EXPECTED =
            List.of("docs/keep.md", "src/Main.java", "sub/app.properties");

    @Test
    void alwaysExcludedDirectoriesAndMinifiedFiles() throws IOException {
        write("Keep.java", "class Keep {}\n");
        write("build/Out.java", "class Out {}\n");
        write("node_modules/x/index.json", "{}\n");
        write(".index/QUERIES.md", "# q\n");
        write(".idea/workspace.xml", "<x/>\n");
        write("site.min.css", "a{}\n");
        write("target/classes/a.properties", "a=1\n");
        write("src/main/java/com/acme/build/Gen.java", "class Gen {}\n");
        write("module/build/gen.txt", "x\n");
        write("module/src/main/resources/build/info.txt", "x\n");

        SelectionResult result = selectWithoutGit(500, List.of());

        assertThat(result.viaGit()).isFalse();
        assertThat(paths(result)).containsExactlyInAnyOrder(
                "Keep.java",
                "module/src/main/resources/build/info.txt",
                "src/main/java/com/acme/build/Gen.java");
    }

    @Test
    void unlistedExtensionsAreIgnoredSilently() throws IOException {
        write("Keep.java", "class Keep {}\n");
        writeBytes("logo.png", new byte[]{1, 0, 2});
        write("Makefile", "all:\n");

        SelectionResult result = selectWithoutGit(500, List.of());

        assertThat(paths(result)).containsExactly("Keep.java");
        assertThat(result.skipped()).isEmpty();
    }

    @Test
    void fallbackWalkerRespectsNestedGitignore() throws IOException {
        createGitignoreProject();

        SelectionResult result = selectWithoutGit(500, List.of());

        assertThat(result.viaGit()).isFalse();
        assertThat(paths(result)).containsExactlyElementsOf(GITIGNORE_EXPECTED);
    }

    @Test
    void gitModeMatchesFallbackWalker() throws IOException {
        GitRunner git = new GitRunner();
        Optional<GitRunner.Result> version = git.run(tmp, "--version");
        assumeTrue(version.isPresent() && version.get().exitCode() == 0, "git is not available");
        Optional<GitRunner.Result> init = git.run(tmp, "init", "-q");
        assumeTrue(init.isPresent() && init.get().exitCode() == 0, "git init failed");

        createGitignoreProject();

        SelectionResult viaGit = new FileSelector(options(500, List.of()), quietLog(), git).select();

        assertThat(viaGit.viaGit()).isTrue();
        assertThat(paths(viaGit)).containsExactlyElementsOf(GITIGNORE_EXPECTED);
    }

    @Test
    void oversizedFileIsSkippedWithSizeStage() throws IOException {
        write("small.java", "class Small {}\n");
        write("big.java", "a".repeat(2048));

        SelectionResult result = selectWithoutGit(1, List.of());

        assertThat(paths(result)).containsExactly("small.java");
        assertThat(result.skipped()).hasSize(1);
        assertThat(result.skipped().get(0).path()).isEqualTo("big.java");
        assertThat(result.skipped().get(0).stage()).isEqualTo("SKIP_SIZE");
    }

    @Test
    void binaryFileIsSkippedWithBinaryStage() throws IOException {
        write("ok.txt", "hello\n");
        writeBytes("bin.txt", new byte[]{'a', 0, 'b'});

        SelectionResult result = selectWithoutGit(500, List.of());

        assertThat(paths(result)).containsExactly("ok.txt");
        assertThat(result.skipped()).hasSize(1);
        assertThat(result.skipped().get(0).path()).isEqualTo("bin.txt");
        assertThat(result.skipped().get(0).stage()).isEqualTo("SKIP_BINARY");
    }

    @Test
    void extraExcludeGlobsAreApplied() throws IOException {
        write("Keep.java", "class Keep {}\n");
        write("a/generated/G.java", "class G {}\n");
        write("generated/H.java", "class H {}\n");
        write("q.sql", "select 1;\n");

        SelectionResult result = selectWithoutGit(500, Arrays.asList("**/generated/**", "*.sql"));

        assertThat(paths(result)).containsExactly("Keep.java");
    }

    @Test
    void nestedRepositoryIsSkippedByWalker() throws IOException {
        write("Keep.java", "class Keep {}\n");
        Files.createDirectories(tmp.resolve("vendor/lib/.git"));
        write("vendor/lib/X.java", "class X {}\n");

        SelectionResult result = selectWithoutGit(500, List.of());

        assertThat(paths(result)).containsExactly("Keep.java");
    }

    @Test
    void selectionIsSortedAndDeterministic() throws IOException {
        write("b/B.java", "class B {}\n");
        write("a/A.java", "class A {}\n");
        write("c.txt", "c\n");

        SelectionResult first = selectWithoutGit(500, List.of());
        SelectionResult second = selectWithoutGit(500, List.of());

        assertThat(paths(first)).containsExactly("a/A.java", "b/B.java", "c.txt");
        assertThat(paths(second)).isEqualTo(paths(first));
    }
}