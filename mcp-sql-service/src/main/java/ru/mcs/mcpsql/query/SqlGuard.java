package ru.mcs.mcpsql.query;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Accepts exactly one SELECT/WITH statement. The connection is read-only as well; this gives clear errors. */
final class SqlGuard {

    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(attach|detach|pragma|load_extension|insert|update|delete|drop|create|alter|vacuum|reindex|analyze)\\b");

    private SqlGuard() {
    }

    static String check(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("empty query");
        }
        String stripped = strip(sql).strip();
        while (stripped.endsWith(";")) {
            stripped = stripped.substring(0, stripped.length() - 1).strip();
        }
        if (stripped.contains(";")) {
            throw new IllegalArgumentException("only one statement is allowed");
        }
        String lower = stripped.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("select") || lower.startsWith("with"))) {
            throw new IllegalArgumentException("only SELECT queries are allowed");
        }
        Matcher m = FORBIDDEN.matcher(lower);
        if (m.find()) {
            throw new IllegalArgumentException("'" + m.group(1) + "' is not allowed in a read-only query");
        }
        String original = sql.strip();
        while (original.endsWith(";")) {
            original = original.substring(0, original.length() - 1).strip();
        }
        return original;
    }

    /** Removes comments and the contents of quoted literals/identifiers. */
    static String strip(String s) {
        StringBuilder out = new StringBuilder();
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char ch = s.charAt(i);
            if (ch == '\'' || ch == '"' || ch == '`') {
                out.append(ch);
                i++;
                while (i < n) {
                    if (s.charAt(i) == ch) {
                        if (i + 1 < n && s.charAt(i + 1) == ch) {
                            i += 2;
                            continue;
                        }
                        break;
                    }
                    i++;
                }
                out.append(ch);
            } else if (ch == '-' && i + 1 < n && s.charAt(i + 1) == '-') {
                while (i < n && s.charAt(i) != '\n') {
                    i++;
                }
                out.append(' ');
            } else if (ch == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 1;
                out.append(' ');
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }
}