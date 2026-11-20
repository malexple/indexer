package ru.mcs.indexer.lang.java;

import org.junit.jupiter.api.Test;
import ru.mcs.indexer.lang.FileContext;
import ru.mcs.indexer.lang.FileFacts;
import ru.mcs.indexer.testing.DemoSources;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JavaLanguageIndexerTest {

    private static FileFacts index(String source) {
        FileFacts facts = new FileFacts();
        new JavaLanguageIndexer().index(new FileContext("demo/core/Manager.java", source, "SOURCE_MAIN"), facts);
        return facts;
    }

    private static List<String> members(FileFacts facts, int typeLocal) {
        return facts.members().stream()
                .filter(m -> m.typeLocal() == typeLocal)
                .map(m -> m.kind() + "|" + m.signature() + "|" + m.lineFrom() + "-" + m.lineTo())
                .toList();
    }

    @Test
    void parsesTypesWithModifiersAndLines() {
        FileFacts facts = index(DemoSources.MANAGER);
        assertThat(facts.errors()).isEmpty();
        assertThat(facts.types().stream()
                .map(t -> t.fqn() + ":" + t.kind() + ":" + t.modifiers() + ":" + t.lineFrom() + "-" + t.lineTo())
                .toList()).containsExactly(
                "demo.core.Manager:CLASS:public:7-21",
                "demo.core.Manager.Inner:CLASS:public static:19-19",
                "demo.core.Manager.Mode:ENUM::20-20",
                "demo.core.Shape:INTERFACE:sealed:22-22",
                "demo.core.Circle:RECORD::23-23",
                "demo.core.Marker:ANNOTATION::24-24");
        assertThat(facts.types().get(0).isPublic()).isTrue();
        assertThat(facts.types().get(3).isPublic()).isFalse();
        assertThat(facts.types().get(1).parentLocal()).isZero();
        assertThat(facts.types().get(0).docSummary()).isEqualTo("Manages editors.");
        assertThat(facts.types().get(0).packageName()).isEqualTo("demo.core");
    }

    @Test
    void parsesMembersAndSignatures() {
        FileFacts facts = index(DemoSources.MANAGER);
        assertThat(members(facts, 0)).containsExactly(
                "FIELD|names:java.util.List|12-12",
                "FIELD|other:java.util.List|12-12",
                "METHOD|run():void|13-15",
                "CONSTRUCTOR|Manager(int,String...)|16-16",
                "INITIALIZER|<clinit>()|17-17",
                "METHOD|open(Map.Entry,int[],boolean):R|18-18");
        assertThat(members(facts, 1)).containsExactly("METHOD|x():void|19-19");
        assertThat(members(facts, 2)).containsExactly(
                "ENUM_CONSTANT|A|20-20", "ENUM_CONSTANT|B|20-20", "METHOD|run():void|20-20");
        assertThat(members(facts, 4)).containsExactly(
                "RECORD_COMPONENT|r:double|23-23",
                "RECORD_COMPONENT|label:String|23-23",
                "CONSTRUCTOR|Circle(double,String)|23-23");
        assertThat(members(facts, 5)).containsExactly("METHOD|value():String|24-24");
        assertThat(facts.members().get(0).modifiers()).isEqualTo("final private");
        assertThat(facts.members().get(2).docSummary()).isEqualTo("Opens it.");
    }

    @Test
    void parsesRelationsImportsAndAnnotations() {
        FileFacts facts = index(DemoSources.MANAGER);
        assertThat(facts.relations().stream()
                .map(r -> facts.types().get(r.typeLocal()).fqn() + "|" + r.relation() + "|" + r.targetName())
                .toList()).containsExactly(
                "demo.core.Manager|EXTENDS|Base",
                "demo.core.Manager|IMPLEMENTS|Runnable",
                "demo.core.Manager|IMPLEMENTS|Comparable",
                "demo.core.Manager.Mode|IMPLEMENTS|Runnable",
                "demo.core.Shape|PERMITS|Circle",
                "demo.core.Shape|PERMITS|Square",
                "demo.core.Circle|IMPLEMENTS|Shape");
        assertThat(facts.imports().stream()
                .map(i -> i.name() + "|" + i.isStatic() + "|" + i.isAsterisk() + "|" + i.line())
                .toList()).containsExactly(
                "java.util.List|false|false|3",
                "java.util.Objects.requireNonNull|true|false|4",
                "java.io.*|false|true|5");
        assertThat(facts.annotations().stream()
                .map(a -> a.ownerKind() + "|" + a.ownerLocal() + "|" + a.name() + "|" + a.args() + "|" + a.line())
                .toList()).containsExactly(
                "TYPE|0|Deprecated|since = \"1\"|10",
                "MEMBER|2|Override|null|14",
                "MEMBER|11|Deprecated|null|23");
    }

    @Test
    void buildsDepsFromImportsWhenResolverIsDisabled() {
        FileFacts facts = index(DemoSources.MANAGER);
        List<String> deps = facts.deps().stream()
                .filter(d -> d.typeLocal() == 0)
                .map(d -> d.depKind() + "|" + d.toFqn() + "|" + d.resolved())
                .toList();
        assertThat(deps).contains(
                "EXTENDS|Base|false", "IMPLEMENTS|Runnable|false", "IMPLEMENTS|Comparable|false",
                "FIELD|java.util.List|false",
                "IMPORT|java.util.List|true", "IMPORT|java.util.Objects|true", "IMPORT|java.io|false");
        assertThat(facts.calls()).isEmpty();
    }

    @Test
    void ignoresAnonymousAndLocalClassesButKeepsTheirCalls() {
        FileFacts facts = index("class A { void m() { Runnable r = new Runnable() { public void run() { go(); } }; "
                + "class Local {} } void go() {} }\n");
        assertThat(facts.types()).hasSize(1);
        assertThat(facts.types().get(0).fqn()).isEqualTo("A");
        assertThat(facts.types().get(0).packageName()).isEmpty();
        assertThat(facts.members()).hasSize(2);
        assertThat(facts.calls().stream().filter(c -> c.calleeName().equals("go")).toList())
                .hasSize(1).allMatch(c -> c.callerLocal() == 0);
    }

    @Test
    void reportsSyntaxErrorsWithoutThrowing() {
        FileFacts facts = index("package demo;\nclass {\n");
        assertThat(facts.errors()).isNotEmpty();
        assertThat(facts.errors().get(0).stage()).isEqualTo("PARSE");
    }
}