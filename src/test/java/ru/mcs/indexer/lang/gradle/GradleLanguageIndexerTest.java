package ru.mcs.indexer.lang.gradle;

import org.junit.jupiter.api.Test;
import ru.mcs.indexer.lang.FileContext;
import ru.mcs.indexer.lang.FileFacts;

import static org.assertj.core.api.Assertions.assertThat;

class GradleLanguageIndexerTest {

    private static FileFacts index(String path, String... lines) {
        FileFacts facts = new FileFacts();
        new GradleLanguageIndexer().index(new FileContext(path, String.join("\n", lines) + "\n", "BUILD"), facts);
        return facts;
    }

    @Test
    void parsesSettingsIncludesAndRootName() {
        FileFacts facts = index("settings.gradle",
                "rootProject.name = 'demo'",
                "include ':core', \":plugins:hello\"",
                "include(':spi')",
                "// include ':ignored'",
                "includeBuild('../other')");
        assertThat(facts.gradleRootName()).isEqualTo("demo");
        assertThat(facts.gradleIncludes()).containsExactly(":core", ":plugins:hello", ":spi");
        assertThat(facts.gradleDeps()).isEmpty();
    }

    @Test
    void parsesDependencyForms() {
        FileFacts facts = index("core/build.gradle",
                "plugins { id 'java' }",
                "dependencies {",
                "    implementation project(':core')",
                "    api(\"com.acme:lib:1.2.3\")",
                "    testImplementation 'org.junit.jupiter:junit-jupiter'",
                "    compileOnly group: 'g', name: 'n', version: '2'",
                "    runtimeOnly(project(path: ':rt'))",
                "    implementation libs.foo",
                "    // implementation 'x:y:1'",
                "    implementation platform('org.x:bom:3')",
                "}");
        assertThat(facts.gradleDeps().stream()
                .map(d -> d.config() + "|" + d.depKind() + "|" + d.target() + "|" + d.line()).toList())
                .containsExactly(
                        "implementation|PROJECT|:core|3",
                        "api|EXTERNAL|com.acme:lib:1.2.3|4",
                        "testImplementation|EXTERNAL|org.junit.jupiter:junit-jupiter|5",
                        "compileOnly|EXTERNAL|g:n:2|6",
                        "runtimeOnly|PROJECT|:rt|7",
                        "implementation|EXTERNAL|org.x:bom:3|10");
    }

    @Test
    void ignoresNonGradleKotlinScripts() {
        FileFacts facts = index("scripts/tool.kts", "implementation(\"a:b:1\")");
        assertThat(facts.gradleDeps()).isEmpty();
    }

    @Test
    void parsesKotlinDsl() {
        FileFacts facts = index("app/build.gradle.kts",
                "dependencies {",
                "    implementation(project(\":core\"))",
                "    testImplementation(\"org.assertj:assertj-core:3.27.7\")",
                "}");
        assertThat(facts.gradleDeps().stream().map(d -> d.depKind() + "|" + d.target()).toList())
                .containsExactly("PROJECT|:core", "EXTERNAL|org.assertj:assertj-core:3.27.7");
    }
}