package ru.mcs.indexer;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record IndexerOptions(
        Path root,
        Path out,
        Set<String> languages,
        Set<String> extensions,
        List<String> excludes,
        int maxFileKb,
        boolean incremental,
        boolean noSource,
        boolean noResolve,
        boolean stats,
        boolean verbose) {

    public static final Set<String> DEFAULT_EXTENSIONS = Set.of(
            "java", "gradle", "kts", "properties", "yml", "yaml", "json",
            "toml", "css", "md", "sql", "xml", "txt");

    public static final Set<String> KNOWN_LANGUAGES = Set.of("java", "javascript", "zig", "kotlin");

    public static final Set<String> IMPLEMENTED_LANGUAGES = Set.of("java");

    public static final Map<String, Set<String>> LANGUAGE_EXTENSIONS = Map.of(
            "java", Set.of("java"),
            "javascript", Set.of("js", "mjs", "cjs"),
            "zig", Set.of("zig"),
            "kotlin", Set.of("kt"));
}