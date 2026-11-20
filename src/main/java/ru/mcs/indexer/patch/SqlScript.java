package ru.mcs.indexer.patch;

import java.util.ArrayList;
import java.util.List;

/** Splits a patch script into statements; understands '...' literals (with '' escapes) and -- comments. */
final class SqlScript {

    private SqlScript() {
    }

    static List<String> split(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        int length = script.length();
        for (int i = 0; i < length; i++) {
            char ch = script.charAt(i);
            if (quoted) {
                current.append(ch);
                if (ch == '\'') {
                    quoted = false;
                }
            } else if (ch == '\'') {
                quoted = true;
                current.append(ch);
            } else if (ch == '-' && i + 1 < length && script.charAt(i + 1) == '-') {
                while (i < length && script.charAt(i) != '\n') {
                    i++;
                }
                current.append('\n');
            } else if (ch == ';') {
                flush(statements, current);
            } else {
                current.append(ch);
            }
        }
        flush(statements, current);
        return statements;
    }

    private static void flush(List<String> statements, StringBuilder current) {
        String statement = current.toString().strip();
        current.setLength(0);
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
    }
}