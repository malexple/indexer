package ru.mcs.indexer.lang.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pragmatic TOML reader: tables, arrays of tables (name[i]), dotted and quoted keys, multi-line arrays
 * and strings. Values of inline tables and arrays are kept as raw text.
 */
final class TomlParser {

    private TomlParser() {
    }

    static void parse(String content, ConfigLanguageIndexer.Sink sink) {
        String[] lines = content.split("\n", -1);
        String table = "";
        Map<String, Integer> arrayCounts = new HashMap<>();
        int i = 0;
        while (i < lines.length && !sink.full()) {
            int startLine = i + 1;
            String line = stripComment(lines[i]).strip();
            i++;
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[[") && line.endsWith("]]")) {
                String name = keyPath(line.substring(2, line.length() - 2));
                int index = arrayCounts.merge(name, 1, Integer::sum) - 1;
                table = name + "[" + index + "]";
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                table = keyPath(line.substring(1, line.length() - 1));
                continue;
            }
            int eq = topLevelEquals(line);
            if (eq < 0) {
                continue;
            }
            String key = keyPath(line.substring(0, eq));
            StringBuilder value = new StringBuilder(line.substring(eq + 1).strip());
            String head = value.toString();
            if (head.startsWith("\"\"\"") || head.startsWith("'''")) {
                String delimiter = head.substring(0, 3);
                while (occurrences(value.toString(), delimiter) < 2 && i < lines.length) {
                    value.append('\n').append(lines[i]);
                    i++;
                }
            } else if (head.startsWith("[") || head.startsWith("{")) {
                while (depth(value.toString()) > 0 && i < lines.length) {
                    value.append(' ').append(stripComment(lines[i]).strip());
                    i++;
                }
            }
            sink.add(table.isEmpty() ? key : table + "." + key, clean(value.toString()), startLine);
        }
    }

    private static String stripComment(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == '\\' && quote == '"') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '#') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static int topLevelEquals(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == '\\' && quote == '"') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '=') {
                return i;
            }
        }
        return -1;
    }

    private static int depth(String text) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\' && quote == '"') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                depth--;
            }
        }
        return depth;
    }

    private static int occurrences(String text, String delimiter) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(delimiter, from)) >= 0) {
            count++;
            from += delimiter.length();
        }
        return count;
    }

    private static String keyPath(String raw) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '.') {
                parts.add(current.toString().strip());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString().strip());
        return String.join(".", parts);
    }

    private static String clean(String raw) {
        String value = raw.strip();
        for (String delimiter : new String[]{"\"\"\"", "'''"}) {
            if (value.startsWith(delimiter)) {
                String inner = value.substring(3);
                if (inner.endsWith(delimiter)) {
                    inner = inner.substring(0, inner.length() - 3);
                }
                return inner.startsWith("\n") ? inner.substring(1) : inner;
            }
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}