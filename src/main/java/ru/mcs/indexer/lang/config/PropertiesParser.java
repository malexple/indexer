package ru.mcs.indexer.lang.config;

/** java.util.Properties syntax with line numbers: comments, continuations, =, : or space separators, escapes. */
final class PropertiesParser {

    private PropertiesParser() {
    }

    static void parse(String content, ConfigLanguageIndexer.Sink sink) {
        String[] lines = content.split("\n", -1);
        int i = 0;
        while (i < lines.length && !sink.full()) {
            int startLine = i + 1;
            String first = lines[i].stripLeading();
            i++;
            if (first.isEmpty() || first.charAt(0) == '#' || first.charAt(0) == '!') {
                continue;
            }
            StringBuilder logical = new StringBuilder(first);
            while (endsWithContinuation(logical) && i < lines.length) {
                logical.setLength(logical.length() - 1);
                logical.append(lines[i].stripLeading());
                i++;
            }
            if (endsWithContinuation(logical)) {
                logical.setLength(logical.length() - 1);
            }
            String text = logical.toString();
            int k = 0;
            StringBuilder key = new StringBuilder();
            while (k < text.length()) {
                char c = text.charAt(k);
                if (c == '\\' && k + 1 < text.length()) {
                    key.append(c).append(text.charAt(k + 1));
                    k += 2;
                    continue;
                }
                if (c == '=' || c == ':' || Character.isWhitespace(c)) {
                    break;
                }
                key.append(c);
                k++;
            }
            while (k < text.length() && Character.isWhitespace(text.charAt(k))) {
                k++;
            }
            if (k < text.length() && (text.charAt(k) == '=' || text.charAt(k) == ':')) {
                k++;
            }
            while (k < text.length() && Character.isWhitespace(text.charAt(k))) {
                k++;
            }
            sink.add(unescape(key.toString()), unescape(text.substring(k)), startLine);
        }
    }

    private static boolean endsWithContinuation(CharSequence text) {
        int slashes = 0;
        for (int i = text.length() - 1; i >= 0 && text.charAt(i) == '\\'; i--) {
            slashes++;
        }
        return slashes % 2 == 1;
    }

    static String unescape(String text) {
        if (text.indexOf('\\') < 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\' || i + 1 >= text.length()) {
                sb.append(c);
                continue;
            }
            char next = text.charAt(++i);
            switch (next) {
                case 't' -> sb.append('\t');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    if (i + 4 < text.length()) {
                        try {
                            sb.append((char) Integer.parseInt(text.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException e) {
                            sb.append('u');
                        }
                    } else {
                        sb.append('u');
                    }
                }
                default -> sb.append(next);
            }
        }
        return sb.toString();
    }
}