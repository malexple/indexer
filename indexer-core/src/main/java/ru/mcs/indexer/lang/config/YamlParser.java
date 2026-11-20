package ru.mcs.indexer.lang.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/** YAML (and JSON, a subset of it) flattened through SnakeYAML's node graph to keep line numbers. */
final class YamlParser {

    private static final int MAX_DEPTH = 64;

    private YamlParser() {
    }

    static void parse(String content, ConfigLanguageIndexer.Sink sink) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(true);
        options.setMaxAliasesForCollections(50);
        Yaml yaml = new Yaml(options);
        List<Node> documents = new ArrayList<>();
        for (Node document : yaml.composeAll(new StringReader(content))) {
            documents.add(document);
        }
        for (int i = 0; i < documents.size(); i++) {
            String prefix = documents.size() > 1 ? "[" + i + "]" : "";
            flatten(prefix, documents.get(i), line(documents.get(i)), sink, 0);
        }
    }

    private static void flatten(String path, Node node, int line, ConfigLanguageIndexer.Sink sink, int depth) {
        if (sink.full() || depth > MAX_DEPTH) {
            return;
        }
        if (node instanceof MappingNode mapping) {
            for (NodeTuple tuple : mapping.getValue()) {
                Node keyNode = tuple.getKeyNode();
                String key = keyNode instanceof ScalarNode scalar ? scalar.getValue() : "?";
                String child = path.isEmpty() ? key : path + "." + key;
                flatten(child, tuple.getValueNode(), line(keyNode), sink, depth + 1);
            }
        } else if (node instanceof SequenceNode sequence) {
            int index = 0;
            for (Node item : sequence.getValue()) {
                flatten(path + "[" + index + "]", item, line(item), sink, depth + 1);
                index++;
            }
        } else if (node instanceof ScalarNode scalar && !path.isEmpty()) {
            sink.add(path, scalar.getValue(), line);
        }
    }

    private static int line(Node node) {
        Mark mark = node.getStartMark();
        return mark == null ? 1 : mark.getLine() + 1;
    }
}