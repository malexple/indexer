package ru.mcs.indexer.lang.java;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Cheap syntactic census of the project sources (no AST): packages, declared type names, declared
 * method names and the FQNs of top-level types derived from file paths. Used to skip symbol-solver
 * lookups that cannot lead to project code. The sets are deliberately supersets: a false positive only
 * costs one solver call. Immutable after scan().
 */
final class ProjectNames {

    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern TYPE = Pattern.compile("\\b(?:class|interface|enum|record|@interface)\\s+([\\w$]+)");
    private static final Pattern METHOD = Pattern.compile("(?<![.\\w$])[\\w$>\\]]+\\s+([\\w$]+)\\s*\\(");
    private static final Pattern RECORD = Pattern.compile("\\brecord\\s+[\\w$]+\\s*(?:<[^>(]*>)?\\s*\\(([^)]*)\\)");
    private static final Pattern LAST_WORD = Pattern.compile("([\\w$]+)\\s*$");

    private final Set<String> packages = new HashSet<>();
    private final Set<String> typeNames = new HashSet<>();
    private final Set<String> methodNames = new HashSet<>(Set.of("values", "valueOf"));
    private final Set<String> knownTypes = new HashSet<>();

    static ProjectNames scan(List<Path> roots) {
        ProjectNames names = new ProjectNames();
        for (Path root : roots) {
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p))
                        .forEach(file -> names.scanFile(root, file));
            } catch (IOException | RuntimeException e) {
                // an unreadable root only makes the filters more permissive for that root's names
            }
        }
        return names;
    }

    private void scanFile(Path root, Path file) {
        StringBuilder fqn = new StringBuilder();
        for (Path part : root.relativize(file)) {
            if (fqn.length() > 0) {
                fqn.append('.');
            }
            fqn.append(part);
        }
        String name = fqn.toString();
        knownTypes.add(name.substring(0, name.length() - ".java".length()));

        String text;
        try {
            text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return;
        }
        Matcher matcher = PACKAGE.matcher(text);
        while (matcher.find()) {
            packages.add(matcher.group(1));
        }
        matcher = TYPE.matcher(text);
        while (matcher.find()) {
            typeNames.add(matcher.group(1));
        }
        matcher = METHOD.matcher(text);
        while (matcher.find()) {
            methodNames.add(matcher.group(1));
        }
        matcher = RECORD.matcher(text);
        while (matcher.find()) {
            for (String component : matcher.group(1).split(",")) {
                Matcher word = LAST_WORD.matcher(component.strip());
                if (word.find()) {
                    methodNames.add(word.group(1));
                }
            }
        }
    }

    Set<String> knownTypes() {
        return knownTypes;
    }

    boolean hasMethod(String name) {
        return methodNames.contains(name);
    }

    boolean hasType(String simpleName) {
        return typeNames.contains(simpleName);
    }

    /** True for JDK names (java., javax.) and for names inside a package declared by the project. */
    boolean isInternalOrJdk(String fqn) {
        if (fqn.startsWith("java.") || fqn.startsWith("javax.")) {
            return true;
        }
        String prefix = fqn;
        int dot = prefix.lastIndexOf('.');
        while (dot > 0) {
            prefix = prefix.substring(0, dot);
            if (packages.contains(prefix)) {
                return true;
            }
            dot = prefix.lastIndexOf('.');
        }
        return false;
    }
}