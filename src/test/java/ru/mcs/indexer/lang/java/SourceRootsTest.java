package ru.mcs.indexer.lang.java;

import org.junit.jupiter.api.Test;
import ru.mcs.indexer.files.SelectedFile;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourceRootsTest {

    private static SelectedFile file(String path) {
        return new SelectedFile(path, Path.of(path), 0);
    }

    @Test
    void discoversMainAndTestRootsOfEveryModule() {
        Path root = Path.of("proj");
        List<Path> roots = SourceRoots.discover(root, List.of(
                file("core/src/main/java/a/B.java"),
                file("core/src/main/java/a/C.java"),
                file("core/src/test/java/a/BTest.java"),
                file("plugins/hello/src/main/java/h/H.java"),
                file("Main.java"),
                file("src/main/resources/x.yml"),
                file("a.txt")));
        assertThat(roots).containsExactly(
                root.resolve("core/src/main/java"),
                root.resolve("core/src/test/java"),
                root.resolve("plugins/hello/src/main/java"));
    }
}