package ru.mcs.indexer.lang.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.mcs.indexer.lang.FileContext;
import ru.mcs.indexer.lang.FileFacts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JavaResolutionTest {

    private static final String GREETER = String.join("\n",
            "package demo.api;",
            "public interface Greeter { String greet(String name); }", "");

    private static final String HELLO = String.join("\n",
            "package demo.impl;",
            "import demo.api.Greeter;",
            "import java.util.ArrayList;",
            "import java.util.List;",
            "public class Hello implements Greeter {",
            "    private final List<String> log = new ArrayList<>();",
            "    @Override public String greet(String name) { log.add(name); return \"Hello, \" + name.trim(); }",
            "}", "");

    private static final String APP = String.join("\n",
            "package demo;",
            "",
            "import demo.api.Greeter;",
            "import demo.impl.Hello;",
            "import java.util.Map;",
            "",
            "public class App {",
            "    private final Greeter greeter = new Hello();",
            "    public String run(String who, Map<String, Hello> cache) {",
            "        Runnable r = Hello::new;",
            "        java.util.function.Function<String, String> f = greeter::greet;",
            "        String s = greeter.greet(who);",
            "        Object o = unknown.Foo.bar();",
            "        return s.trim();",
            "    }",
            "}", "");

    @TempDir
    Path tmp;

    private Path sourceRoot() throws IOException {
        Path root = tmp.resolve("src/main/java");
        write(root, "demo/api/Greeter.java", GREETER);
        write(root, "demo/impl/Hello.java", HELLO);
        write(root, "demo/App.java", APP);
        return root;
    }

    private static void write(Path root, String rel, String content) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static FileFacts index(JavaLanguageIndexer indexer, String path, String content) {
        FileFacts facts = new FileFacts();
        indexer.index(new FileContext(path, content, "SOURCE_MAIN"), facts);
        return facts;
    }

    private static List<FileFacts.CallRow> calls(FileFacts facts, String kind, String name) {
        return facts.calls().stream()
                .filter(c -> c.callKind().equals(kind) && c.calleeName().equals(name)).toList();
    }

    @Test
    void resolvesCallsAcrossFilesAndJdk() throws IOException {
        JavaLanguageIndexer indexer = new JavaLanguageIndexer(List.of(sourceRoot()), true);
        FileFacts facts = index(indexer, "demo/App.java", APP);
        assertThat(facts.errors()).isEmpty();

        assertThat(facts.members().stream().map(m -> m.kind() + "|" + m.signature()).toList()).containsExactly(
                "FIELD|greeter:demo.api.Greeter",
                "METHOD|run(java.lang.String,java.util.Map):java.lang.String");

        List<FileFacts.CallRow> created = calls(facts, "NEW", "Hello");
        assertThat(created).hasSize(1);
        assertThat(created.get(0).calleeOwnerFqn()).isEqualTo("demo.impl.Hello");
        assertThat(created.get(0).resolved()).isTrue();
        assertThat(created.get(0).callerLocal()).isZero();

        List<FileFacts.CallRow> greet = calls(facts, "METHOD", "greet");
        assertThat(greet).hasSize(1);
        assertThat(greet.get(0).calleeOwnerFqn()).isEqualTo("demo.api.Greeter");
        assertThat(greet.get(0).calleeSignature()).isEqualTo("greet(java.lang.String):java.lang.String");
        assertThat(greet.get(0).resolved()).isTrue();
        assertThat(greet.get(0).line()).isEqualTo(12);
        assertThat(greet.get(0).callerLocal()).isEqualTo(1);

        List<FileFacts.CallRow> trim = calls(facts, "METHOD", "trim");
        assertThat(trim).hasSize(1);
        assertThat(trim.get(0).calleeOwnerFqn()).isEqualTo("java.lang.String");
        assertThat(trim.get(0).calleeSignature()).isEqualTo("trim():java.lang.String");

        List<FileFacts.CallRow> bar = calls(facts, "METHOD", "bar");
        assertThat(bar).hasSize(1);
        assertThat(bar.get(0).resolved()).isFalse();
        assertThat(bar.get(0).calleeOwnerFqn()).isNull();

        List<FileFacts.CallRow> constructorRef = calls(facts, "CONSTRUCTOR_REF", "Hello");
        assertThat(constructorRef).hasSize(1);
        assertThat(constructorRef.get(0).calleeOwnerFqn()).isEqualTo("demo.impl.Hello");
        assertThat(calls(facts, "METHOD_REF", "greet")).hasSize(1);
    }

    @Test
    void buildsResolvedTypeDeps() throws IOException {
        JavaLanguageIndexer indexer = new JavaLanguageIndexer(List.of(sourceRoot()), true);
        FileFacts facts = index(indexer, "demo/App.java", APP);
        List<String> deps = facts.deps().stream()
                .filter(d -> d.typeLocal() == 0)
                .map(d -> d.depKind() + "|" + d.toFqn() + "|" + d.resolved()).toList();
        assertThat(deps).contains(
                "FIELD|demo.api.Greeter|true", "PARAM|java.util.Map|true", "PARAM|demo.impl.Hello|true",
                "NEW|demo.impl.Hello|true", "CALL|demo.api.Greeter|true",
                "IMPORT|demo.api.Greeter|true", "IMPORT|java.util.Map|true");
        assertThat(facts.deps()).noneMatch(d -> d.toFqn().equals("java.lang.String"));
    }

    @Test
    void resolvesRelationTargets() throws IOException {
        JavaLanguageIndexer indexer = new JavaLanguageIndexer(List.of(sourceRoot()), true);
        FileFacts facts = index(indexer, "demo/impl/Hello.java", HELLO);
        assertThat(facts.relations()).hasSize(1);
        FileFacts.RelationRow relation = facts.relations().get(0);
        assertThat(relation.relation()).isEqualTo("IMPLEMENTS");
        assertThat(relation.targetFqn()).isEqualTo("demo.api.Greeter");
        assertThat(relation.resolved()).isTrue();
    }

    @Test
    void withoutResolverUsesImportsAndMarksEverythingUnresolved() throws IOException {
        JavaLanguageIndexer indexer = new JavaLanguageIndexer(List.of(sourceRoot()), false);
        FileFacts facts = index(indexer, "demo/App.java", APP);
        assertThat(facts.members().get(1).signature()).isEqualTo("run(String,java.util.Map):String");
        assertThat(facts.calls()).isNotEmpty().allMatch(c -> !c.resolved());
        List<FileFacts.CallRow> created = calls(facts, "NEW", "Hello");
        assertThat(created.get(0).calleeOwnerFqn()).isEqualTo("demo.impl.Hello");
    }
}