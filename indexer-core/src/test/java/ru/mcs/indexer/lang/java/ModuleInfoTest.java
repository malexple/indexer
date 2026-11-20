package ru.mcs.indexer.lang.java;

import org.junit.jupiter.api.Test;
import ru.mcs.indexer.lang.FileContext;
import ru.mcs.indexer.lang.FileFacts;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleInfoTest {

    private static FileFacts index(String source) {
        FileFacts facts = new FileFacts();
        new JavaLanguageIndexer().index(new FileContext("src/main/java/module-info.java", source, "SOURCE_MAIN"), facts);
        return facts;
    }

    @Test
    void parsesAllDirectiveKinds() {
        FileFacts facts = index(String.join("\n",
                "module demo.app {",
                "    requires transitive java.sql;",
                "    requires static lombok;",
                "    requires java.base;",
                "    exports demo.api;",
                "    exports demo.internal to demo.friend, demo.other;",
                "    opens demo.model to com.fasterxml.jackson.databind;",
                "    uses demo.spi.Service;",
                "    provides demo.spi.Service with demo.impl.A, demo.impl.B;",
                "}", ""));
        assertThat(facts.errors()).isEmpty();
        assertThat(facts.types()).isEmpty();
        assertThat(facts.javaModule().name()).isEqualTo("demo.app");
        assertThat(facts.javaModule().open()).isFalse();
        assertThat(facts.directives().stream()
                .map(d -> d.directive() + "|" + d.target() + "|" + d.extra() + "|" + d.line()).toList())
                .containsExactly(
                        "requires|java.sql|transitive|2",
                        "requires|lombok|static|3",
                        "requires|java.base|null|4",
                        "exports|demo.api|null|5",
                        "exports|demo.internal|to demo.friend, demo.other|6",
                        "opens|demo.model|to com.fasterxml.jackson.databind|7",
                        "uses|demo.spi.Service|null|8",
                        "provides|demo.spi.Service|with demo.impl.A, demo.impl.B|9");
    }

    @Test
    void recognizesOpenModules() {
        FileFacts facts = index("open module demo.open {\n}\n");
        assertThat(facts.javaModule().name()).isEqualTo("demo.open");
        assertThat(facts.javaModule().open()).isTrue();
        assertThat(facts.directives()).isEmpty();
    }
}