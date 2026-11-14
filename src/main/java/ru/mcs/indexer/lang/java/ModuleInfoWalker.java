package ru.mcs.indexer.lang.java;

import com.github.javaparser.ast.modules.ModuleDeclaration;
import com.github.javaparser.ast.modules.ModuleDirective;
import ru.mcs.indexer.lang.FileFacts;
import ru.mcs.indexer.lang.FileFacts.DirectiveRow;
import ru.mcs.indexer.lang.FileFacts.ModuleRow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fills java_modules / module_directives rows from a module-info.java declaration. */
final class ModuleInfoWalker {

    private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");
    private static final Pattern HEAD = Pattern.compile("^(requires|exports|opens|uses|provides)\\s+(.*?)\\s*;?\\s*$");
    private static final Pattern TO = Pattern.compile("^(\\S+)(?:\\s+to\\s+(.+))?$");
    private static final Pattern WITH = Pattern.compile("^(\\S+)\\s+with\\s+(.+)$");

    private ModuleInfoWalker() {
    }

    static void index(ModuleDeclaration module, FileFacts out) {
        out.setJavaModule(new ModuleRow(module.getNameAsString(), module.isOpen(),
                module.getRange().map(r -> r.begin.line).orElse(1)));
        for (ModuleDirective directive : module.getDirectives()) {
            String text = BLOCK_COMMENT.matcher(directive.toString()).replaceAll("");
            text = LINE_COMMENT.matcher(text).replaceAll("");
            text = text.replaceAll("\\s+", " ").strip();
            Matcher head = HEAD.matcher(text);
            if (!head.matches()) {
                continue;
            }
            int line = directive.getRange().map(r -> r.begin.line).orElse(1);
            String kind = head.group(1);
            String rest = head.group(2);
            switch (kind) {
                case "requires" -> addRequires(out, rest, line);
                case "exports", "opens" -> {
                    Matcher to = TO.matcher(rest);
                    if (to.matches()) {
                        String extra = to.group(2) == null ? null : "to " + names(to.group(2));
                        out.addDirective(new DirectiveRow(kind, to.group(1), extra, line));
                    }
                }
                case "uses" -> out.addDirective(new DirectiveRow(kind, rest, null, line));
                case "provides" -> {
                    Matcher with = WITH.matcher(rest);
                    if (with.matches()) {
                        out.addDirective(new DirectiveRow(kind, with.group(1), "with " + names(with.group(2)), line));
                    } else {
                        out.addDirective(new DirectiveRow(kind, rest, null, line));
                    }
                }
                default -> {
                }
            }
        }
    }

    private static void addRequires(FileFacts out, String rest, int line) {
        String[] tokens = rest.split(" ");
        List<String> modifiers = new ArrayList<>();
        for (int i = 0; i < tokens.length - 1; i++) {
            modifiers.add(tokens[i]);
        }
        String target = tokens[tokens.length - 1];
        out.addDirective(new DirectiveRow("requires", target,
                modifiers.isEmpty() ? null : String.join(" ", modifiers), line));
    }

    private static String names(String list) {
        return String.join(", ", Arrays.stream(list.split(",")).map(String::strip).toList());
    }
}