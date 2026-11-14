package ru.mcs.indexer.files;

import java.util.Locale;

public final class FileClassifier {

    private FileClassifier() {
    }

    public static String lang(String path) {
        String name = fileName(path).toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "text";
        }
        String ext = name.substring(dot + 1);
        return switch (ext) {
            case "java" -> "java";
            case "gradle" -> "gradle";
            case "kts" -> name.endsWith(".gradle.kts") ? "gradle" : "kotlin";
            case "kt" -> "kotlin";
            case "yml", "yaml" -> "yaml";
            case "js", "mjs", "cjs" -> "javascript";
            default -> ext;
        };
    }

    public static String kind(String path) {
        String name = fileName(path).toLowerCase(Locale.ROOT);
        if (name.endsWith(".gradle") || name.endsWith(".gradle.kts")) {
            return "BUILD";
        }
        String[] segments = path.split("/");
        for (int i = 0; i + 2 < segments.length - 1; i++) {
            if (!segments[i].equals("src")) {
                continue;
            }
            String sourceSet = segments[i + 1];
            String dir = segments[i + 2];
            if (dir.equals("java")) {
                if (sourceSet.equals("main")) {
                    return "SOURCE_MAIN";
                }
                if (sourceSet.equals("test")) {
                    return "SOURCE_TEST";
                }
            } else if (dir.equals("resources")) {
                return "RESOURCE";
            }
        }
        if (name.endsWith(".md")) {
            return "DOC";
        }
        return "OTHER";
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}