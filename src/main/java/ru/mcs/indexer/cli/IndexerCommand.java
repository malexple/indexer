package ru.mcs.indexer.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import ru.mcs.indexer.IndexerApp;
import ru.mcs.indexer.IndexerOptions;
import ru.mcs.indexer.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(
        name = "Indexer",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        sortOptions = false,
        description = "Indexes a project directory into a single SQLite file for AI assistants.")
public final class IndexerCommand implements Callable<Integer> {

    @Option(names = {"-r", "--root"}, paramLabel = "DIR",
            description = "Project root (default: current directory).")
    private Path root;

    @Option(names = {"-o", "--out"}, paramLabel = "FILE",
            description = "Database path (default: <root>/.index/index.db).")
    private Path out;

    @Option(names = {"-l", "--lang"}, split = ",", defaultValue = "java", paramLabel = "LANG",
            description = "Languages parsed into AST, comma-separated (default: ${DEFAULT-VALUE}). "
                    + "Known: java, javascript, zig, kotlin.")
    private List<String> languages = new ArrayList<>();

    @Option(names = "--include-ext", split = ",", paramLabel = "EXT",
            description = "Extra file extensions stored as text (no AST).")
    private List<String> includeExt = new ArrayList<>();

    @Option(names = "--exclude", split = ",", paramLabel = "GLOB",
            description = "Extra gitignore-style exclusion patterns.")
    private List<String> excludes = new ArrayList<>();

    @Option(names = "--max-file-kb", defaultValue = "500", paramLabel = "KB",
            description = "Skip files larger than this (default: ${DEFAULT-VALUE}).")
    private int maxFileKb;

    @Option(names = "--incremental", description = "Reindex only changed files (by SHA-256).")
    private boolean incremental;

    @Option(names = "--no-source", description = "Do not store file text (files.content = NULL).")
    private boolean noSource;

    @Option(names = "--no-resolve", description = "Do not use SymbolSolver (resolved = 0).")
    private boolean noResolve;

    @Option(names = "--stats", description = "Print summary after indexing.")
    private boolean stats;

    @Option(names = {"-v", "--verbose"}, description = "Verbose log.")
    private boolean verbose;

    public boolean isVerbose() {
        return verbose;
    }

    List<String> languagesRaw() {
        return languages;
    }

    @Override
    public Integer call() throws IOException {
        Log log = new Log(verbose);
        IndexerOptions options;
        try {
            options = buildOptions(log);
        } catch (IllegalArgumentException e) {
            log.error("error: " + e.getMessage());
            return 1;
        }
        return IndexerApp.run(options, log);
    }

    private IndexerOptions buildOptions(Log log) {
        Path rootAbs = (root == null ? Path.of("") : root).toAbsolutePath().normalize();
        if (!Files.isDirectory(rootAbs)) {
            throw new IllegalArgumentException("root is not a directory: " + rootAbs);
        }
        Path outAbs = out == null
                ? rootAbs.resolve(".index").resolve("index.db")
                : out.toAbsolutePath().normalize();

        if (maxFileKb <= 0) {
            throw new IllegalArgumentException("--max-file-kb must be positive, got " + maxFileKb);
        }

        Set<String> langs = new LinkedHashSet<>();
        for (String raw : languages) {
            String lang = raw.trim().toLowerCase(Locale.ROOT);
            if (lang.isEmpty()) {
                continue;
            }
            if (!IndexerOptions.KNOWN_LANGUAGES.contains(lang)) {
                throw new IllegalArgumentException("unknown language '" + lang + "', known: "
                        + String.join(", ", new java.util.TreeSet<>(IndexerOptions.KNOWN_LANGUAGES)));
            }
            if (!IndexerOptions.IMPLEMENTED_LANGUAGES.contains(lang)) {
                log.warn("language '" + lang + "' is not implemented yet and will be ignored");
                continue;
            }
            langs.add(lang);
        }
        if (langs.isEmpty()) {
            throw new IllegalArgumentException("no usable languages in --lang");
        }

        Set<String> extensions = new LinkedHashSet<>(IndexerOptions.DEFAULT_EXTENSIONS);
        for (String lang : langs) {
            extensions.addAll(IndexerOptions.LANGUAGE_EXTENSIONS.get(lang));
        }
        for (String raw : includeExt) {
            String ext = raw.trim().toLowerCase(Locale.ROOT);
            while (ext.startsWith(".")) {
                ext = ext.substring(1);
            }
            if (!ext.isEmpty()) {
                extensions.add(ext);
            }
        }

        List<String> excl = new ArrayList<>();
        for (String raw : excludes) {
            String pattern = raw.trim();
            if (!pattern.isEmpty()) {
                excl.add(pattern);
            }
        }

        return new IndexerOptions(rootAbs, outAbs, langs, Set.copyOf(extensions), List.copyOf(excl),
                maxFileKb, incremental, noSource, noResolve, stats, verbose);
    }
}