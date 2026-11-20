package ru.mcs.indexer.lang.config;

import org.junit.jupiter.api.Test;
import ru.mcs.indexer.lang.FileContext;
import ru.mcs.indexer.lang.FileFacts;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigParsersTest {

    private static FileFacts index(String path, String... lines) {
        FileFacts facts = new FileFacts();
        new ConfigLanguageIndexer().index(new FileContext(path, String.join("\n", lines) + "\n", "RESOURCE"), facts);
        return facts;
    }

    private static List<String> entries(FileFacts facts) {
        return facts.configs().stream().map(c -> c.keyPath() + "|" + c.value() + "|" + c.line()).toList();
    }

    @Test
    void flattensYamlWithLineNumbers() {
        FileFacts facts = index("plugin.yml",
                "name: demo",
                "contributions:",
                "  actions:",
                "    - id: open",
                "      title: Open file",
                "    - id: save",
                "  empty:",
                "list: [1, 2]");
        assertThat(facts.errors()).isEmpty();
        assertThat(entries(facts)).containsExactly(
                "name|demo|1",
                "contributions.actions[0].id|open|4",
                "contributions.actions[0].title|Open file|5",
                "contributions.actions[1].id|save|6",
                "contributions.empty||7",
                "list[0]|1|8",
                "list[1]|2|8");
    }

    @Test
    void flattensJson() {
        FileFacts facts = index("data.json", "{\"a\": {\"b\": [1, \"x\"]}, \"c\": true}");
        assertThat(entries(facts)).containsExactly("a.b[0]|1|1", "a.b[1]|x|1", "c|true|1");
    }

    @Test
    void parsesPropertiesWithEscapesAndContinuations() {
        FileFacts facts = index("messages_ru.properties",
                "# comment",
                "app.name = Demo",
                "greeting:Hello \\",
                "   world",
                "title.ru=\\u041f\\u0440\\u0438\\u0432\\u0435\\u0442",
                "empty",
                "path=C\\:\\\\dir");
        assertThat(entries(facts)).containsExactly(
                "app.name|Demo|2",
                "greeting|Hello world|3",
                "title.ru|Привет|5",
                "empty||6",
                "path|C:\\dir|7");
    }

    @Test
    void parsesTomlTablesAndArraysOfTables() {
        FileFacts facts = index("config.toml",
                "title = \"TOML Example\"",
                "[owner]",
                "name = \"Tom\"",
                "dob = 1979-05-27",
                "[database]",
                "ports = [ 8000, 8001 ]",
                "[[servers]]",
                "ip = \"10.0.0.1\"",
                "[[servers]]",
                "ip = \"10.0.0.2\" # comment",
                "[tool.\"my.lib\"]",
                "x = 'raw'");
        assertThat(entries(facts)).containsExactly(
                "title|TOML Example|1",
                "owner.name|Tom|3",
                "owner.dob|1979-05-27|4",
                "database.ports|[ 8000, 8001 ]|6",
                "servers[0].ip|10.0.0.1|8",
                "servers[1].ip|10.0.0.2|10",
                "tool.my.lib.x|raw|12");
    }

    @Test
    void brokenYamlIsReportedNotThrown() {
        FileFacts facts = index("broken.yml", "a: [1, 2", "b: }");
        assertThat(facts.errors()).isNotEmpty();
        assertThat(facts.errors().get(0).stage()).isEqualTo("PARSE");
    }
}