package ru.mcs.indexer.files;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FileClassifierTest {

    @Test
    void kindByPath() {
        assertThat(FileClassifier.kind("plugins/hello/src/main/java/a/B.java")).isEqualTo("SOURCE_MAIN");
        assertThat(FileClassifier.kind("core/src/test/java/X.java")).isEqualTo("SOURCE_TEST");
        assertThat(FileClassifier.kind("src/main/java/Main.java")).isEqualTo("SOURCE_MAIN");
        assertThat(FileClassifier.kind("core/src/main/resources/plugin.yml")).isEqualTo("RESOURCE");
        assertThat(FileClassifier.kind("core/src/test/resources/a.properties")).isEqualTo("RESOURCE");
        assertThat(FileClassifier.kind("build.gradle")).isEqualTo("BUILD");
        assertThat(FileClassifier.kind("core/build.gradle.kts")).isEqualTo("BUILD");
        assertThat(FileClassifier.kind("README.md")).isEqualTo("DOC");
        assertThat(FileClassifier.kind("Main.java")).isEqualTo("OTHER");
        assertThat(FileClassifier.kind("src/main/java")).isEqualTo("OTHER");
    }

    @Test
    void langByExtension() {
        assertThat(FileClassifier.lang("a/B.java")).isEqualTo("java");
        assertThat(FileClassifier.lang("a/plugin.yml")).isEqualTo("yaml");
        assertThat(FileClassifier.lang("a/x.YAML")).isEqualTo("yaml");
        assertThat(FileClassifier.lang("build.gradle")).isEqualTo("gradle");
        assertThat(FileClassifier.lang("build.gradle.kts")).isEqualTo("gradle");
        assertThat(FileClassifier.lang("Foo.kts")).isEqualTo("kotlin");
        assertThat(FileClassifier.lang("a/app.js")).isEqualTo("javascript");
        assertThat(FileClassifier.lang("a/messages.properties")).isEqualTo("properties");
        assertThat(FileClassifier.lang("a/NOTES")).isEqualTo("text");
    }
}