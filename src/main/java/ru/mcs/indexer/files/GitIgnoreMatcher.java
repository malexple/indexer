package ru.mcs.indexer.files;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Gitignore semantics without git. Rules from several .gitignore files are registered with
 * their base directory; deeper files take precedence, the last matching rule in a file wins,
 * and a path under an ignored directory is always ignored.
 */
public final class GitIgnoreMatcher {

    private record Rule(Pattern pattern, boolean negated, boolean dirOnly) {
    }

    private record RuleSet(String base, int depth, List<Rule> rules) {
    }

    private final List<RuleSet> sets = new ArrayList<>();

    public void addRules(String baseDir, List<String> lines) {
        String base = normalize(baseDir);
        List<Rule> rules = new ArrayList<>();
        for (String line : lines) {
            Rule rule = parseLine(line);
            if (rule != null) {
                rules.add(rule);
            }
        }
        if (rules.isEmpty()) {
            return;
        }
        sets.add(new RuleSet(base, depth(base), rules));
        sets.sort(Comparator.comparingInt(RuleSet::depth));
    }

    public boolean isIgnored(String relPath, boolean isDirectory) {
        String path = normalize(relPath);
        if (path.isEmpty()) {
            return false;
        }
        int slash = path.indexOf('/');
        while (slash >= 0) {
            if (matchesDirect(path.substring(0, slash), true)) {
                return true;
            }
            slash = path.indexOf('/', slash + 1);
        }
        return matchesDirect(path, isDirectory);
    }

    private boolean matchesDirect(String path, boolean isDirectory) {
        boolean ignored = false;
        for (RuleSet set : sets) {
            String relative;
            if (set.base().isEmpty()) {
                relative = path;
            } else if (path.startsWith(set.base() + "/")) {
                relative = path.substring(set.base().length() + 1);
            } else {
                continue;
            }
            for (Rule rule : set.rules()) {
                if (rule.dirOnly() && !isDirectory) {
                    continue;
                }
                if (rule.pattern().matcher(relative).matches()) {
                    ignored = !rule.negated();
                }
            }
        }
        return ignored;
    }

    static Rule parseLine(String raw) {
        String line = raw;
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        int end = line.length();
        while (end > 0 && line.charAt(end - 1) == ' ') {
            if (end >= 2 && line.charAt(end - 2) == '\\') {
                break;
            }
            end--;
        }
        line = line.substring(0, end);
        if (line.isEmpty() || line.charAt(0) == '#') {
            return null;
        }
        boolean negated = false;
        if (line.charAt(0) == '!') {
            negated = true;
            line = line.substring(1);
        }
        boolean dirOnly = false;
        if (line.endsWith("/")) {
            dirOnly = true;
            line = line.substring(0, line.length() - 1);
        }
        if (line.isEmpty()) {
            return null;
        }
        boolean anchored = line.indexOf('/') >= 0;
        if (line.startsWith("/")) {
            line = line.substring(1);
        }
        if (line.isEmpty()) {
            return null;
        }
        String body = globToRegex(line);
        String regex = anchored ? body : "(?:.*/)?" + body;
        return new Rule(Pattern.compile(regex), negated, dirOnly);
    }

    static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        int n = glob.length();
        int i = 0;
        while (i < n) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    int j = i;
                    while (j < n && glob.charAt(j) == '*') {
                        j++;
                    }
                    int run = j - i;
                    boolean atStart = i == 0 || glob.charAt(i - 1) == '/';
                    boolean atEnd = j == n || glob.charAt(j) == '/';
                    if (run >= 2 && atStart && atEnd) {
                        if (j < n) {
                            sb.append("(?:.*/)?");
                            j++;
                        } else {
                            sb.append(".*");
                        }
                    } else {
                        sb.append("[^/]*");
                    }
                    i = j;
                }
                case '?' -> {
                    sb.append("[^/]");
                    i++;
                }
                case '[' -> {
                    int close = findClassEnd(glob, i);
                    if (close < 0) {
                        sb.append("\\[");
                        i++;
                    } else {
                        appendClass(sb, glob.substring(i + 1, close));
                        i = close + 1;
                    }
                }
                case '\\' -> {
                    if (i + 1 < n) {
                        appendLiteral(sb, glob.charAt(i + 1));
                        i += 2;
                    } else {
                        sb.append("\\\\");
                        i++;
                    }
                }
                default -> {
                    appendLiteral(sb, c);
                    i++;
                }
            }
        }
        return sb.toString();
    }

    private static int findClassEnd(String glob, int open) {
        int n = glob.length();
        int j = open + 1;
        if (j < n && (glob.charAt(j) == '!' || glob.charAt(j) == '^')) {
            j++;
        }
        if (j < n && glob.charAt(j) == ']') {
            j++;
        }
        while (j < n && glob.charAt(j) != ']') {
            j++;
        }
        return j < n ? j : -1;
    }

    private static void appendClass(StringBuilder sb, String body) {
        sb.append('[');
        int start = 0;
        if (!body.isEmpty() && (body.charAt(0) == '!' || body.charAt(0) == '^')) {
            sb.append('^');
            start = 1;
        }
        for (int k = start; k < body.length(); k++) {
            char c = body.charAt(k);
            if (c == '\\' || c == '[' || c == '&') {
                sb.append('\\');
            }
            sb.append(c);
        }
        sb.append(']');
    }

    private static void appendLiteral(StringBuilder sb, char c) {
        if (Character.isLetterOrDigit(c) || c == '/' || c == '_' || c == '-') {
            sb.append(c);
        } else {
            sb.append('\\').append(c);
        }
    }

    private static String normalize(String path) {
        String p = path.replace('\\', '/');
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static int depth(String base) {
        if (base.isEmpty()) {
            return 0;
        }
        int depth = 1;
        for (int i = 0; i < base.length(); i++) {
            if (base.charAt(i) == '/') {
                depth++;
            }
        }
        return depth;
    }
}