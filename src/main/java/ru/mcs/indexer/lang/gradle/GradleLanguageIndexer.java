package ru.mcs.indexer.lang.gradle;

import ru.mcs.indexer.lang.FileContext;
import ru.mcs.indexer.lang.FileFacts;
import ru.mcs.indexer.lang.FileFacts.GradleDepRow;
import ru.mcs.indexer.lang.LanguageIndexer;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex-only reader of Gradle scripts (no Tooling API, nothing is executed): settings include/rootProject.name
 * and dependency declarations of the common forms. Stateless, thread-safe.
 */
public final class GradleLanguageIndexer implements LanguageIndexer {

    private static final String CONFIG = "(?:[a-z]\\w*)?(?:[Ii]mplementation|[Aa]pi|[Cc]ompileOnly(?:Api)?"
            + "|[Rr]untimeOnly|[Aa]nnotationProcessor|[Cc]ompile|[Rr]untime|[Kk]apt|[Cc]lasspath)";
    private static final Pattern DEP_LINE = Pattern.compile("^\\s*(" + CONFIG + ")\\b\\s*\\(?\\s*(.*)$");
    private static final Pattern PROJECT =
            Pattern.compile("project\\s*\\(\\s*(?:path\\s*[:=]\\s*)?['\"](:[^'\"]*)['\"]");
    private static final Pattern MAP = Pattern.compile("group\\s*[:=]\\s*['\"]([^'\"]+)['\"]\\s*,\\s*name\\s*[:=]\\s*"
            + "['\"]([^'\"]+)['\"](?:\\s*,\\s*version\\s*[:=]\\s*['\"]([^'\"]+)['\"])?");
    private static final Pattern COORD = Pattern.compile("['\"]([^'\"\\s:]+:[^'\"\\s:]+(?::[^'\"\\s]+)?)['\"]");
    private static final Pattern INCLUDE =
            Pattern.compile("(?m)^\\s*include\\s*\\(?\\s*((?:['\"][^'\"\\n]+['\"]\\s*,?\\s*)+)");
    private static final Pattern QUOTED = Pattern.compile("['\"]([^'\"\\n]+)['\"]");
    private static final Pattern ROOT_NAME = Pattern.compile("rootProject\\.name\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern LINE_COMMENT = Pattern.compile("(?m)(^|\\s)//.*$");

    @Override
    public String language() {
        return "gradle";
    }

    @Override
    public Set<String> extensions() {
        return Set.of("gradle", "kts");
    }

    @Override
    public void index(FileContext ctx, FileFacts out) {
        String path = ctx.path();
        if (!path.endsWith(".gradle") && !path.endsWith(".gradle.kts")) {
            return;
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.startsWith("settings.")) {
            parseSettings(ctx.content(), out);
            return;
        }
        parseDependencies(ctx.content(), out);
    }

    private static void parseSettings(String content, FileFacts out) {
        String text = LINE_COMMENT.matcher(content).replaceAll("$1");
        Matcher root = ROOT_NAME.matcher(text);
        if (root.find()) {
            out.setGradleRootName(root.group(1));
        }
        Matcher include = INCLUDE.matcher(text);
        while (include.find()) {
            Matcher quoted = QUOTED.matcher(include.group(1));
            while (quoted.find()) {
                out.addGradleInclude(quoted.group(1).strip());
            }
        }
    }

    private static void parseDependencies(String content, FileFacts out) {
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].strip();
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                continue;
            }
            Matcher line = DEP_LINE.matcher(lines[i]);
            if (!line.matches()) {
                continue;
            }
            String config = line.group(1);
            String rest = line.group(2);
            Matcher project = PROJECT.matcher(rest);
            if (project.find()) {
                out.addGradleDep(new GradleDepRow(config, "PROJECT", project.group(1), i + 1));
                continue;
            }
            Matcher map = MAP.matcher(rest);
            if (map.find()) {
                String target = map.group(1) + ":" + map.group(2) + (map.group(3) == null ? "" : ":" + map.group(3));
                out.addGradleDep(new GradleDepRow(config, "EXTERNAL", target, i + 1));
                continue;
            }
            Matcher coordinate = COORD.matcher(rest);
            if (coordinate.find()) {
                out.addGradleDep(new GradleDepRow(config, "EXTERNAL", coordinate.group(1), i + 1));
            }
        }
    }
}