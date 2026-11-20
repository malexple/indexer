package ru.mcs.indexer.lang.java;

import java.util.regex.Pattern;

public final class JavadocSummary {

    private static final int MAX_LENGTH = 300;
    private static final Pattern INLINE_TAG = Pattern.compile("\\{@\\w+\\s+([^}]*)\\}");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private JavadocSummary() {
    }

    /** First sentence of a Javadoc comment body (the text between the comment delimiters), or null. */
    public static String firstSentence(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (String line : raw.split("\n", -1)) {
            String s = line.strip();
            while (s.startsWith("*")) {
                s = s.substring(1).stripLeading();
            }
            if (s.startsWith("@")) {
                break;
            }
            if (s.isEmpty()) {
                if (text.length() > 0) {
                    break;
                }
                continue;
            }
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(s);
        }
        String cleaned = INLINE_TAG.matcher(text).replaceAll("$1");
        cleaned = HTML_TAG.matcher(cleaned).replaceAll("");
        cleaned = SPACES.matcher(cleaned).replaceAll(" ").strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            boolean end = c == '.' || c == '!' || c == '?';
            if (end && (i + 1 == cleaned.length() || Character.isWhitespace(cleaned.charAt(i + 1)))) {
                cleaned = cleaned.substring(0, i + 1);
                break;
            }
        }
        if (cleaned.length() > MAX_LENGTH) {
            cleaned = cleaned.substring(0, MAX_LENGTH);
        }
        return cleaned;
    }
}