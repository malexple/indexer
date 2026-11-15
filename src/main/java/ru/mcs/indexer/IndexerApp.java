package ru.mcs.indexer;

import ru.mcs.indexer.db.Db;
import ru.mcs.indexer.db.IndexWriter;
import ru.mcs.indexer.db.PriorFile;
import ru.mcs.indexer.db.Schema;
import ru.mcs.indexer.files.FileSelector;
import ru.mcs.indexer.files.GitInfo;
import ru.mcs.indexer.files.GitRunner;
import ru.mcs.indexer.files.SelectedFile;
import ru.mcs.indexer.files.SelectionResult;
import ru.mcs.indexer.files.SkippedFile;
import ru.mcs.indexer.lang.LanguageRegistry;
import ru.mcs.indexer.lang.java.SourceRoots;
import ru.mcs.indexer.pipeline.FileIndexPipeline;
import ru.mcs.indexer.util.Log;
import ru.mcs.indexer.util.Versions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class IndexerApp {

    /** Symbol resolution duplicates per-thread solver caches: measured optimum is 2 workers. */
    private static final int DEFAULT_RESOLVE_WORKERS = 2;
    private static final int MAX_PARSE_WORKERS = 8;
    private static final String IN_PROGRESS = "indexing in progress";
    private static final int FULL_RUN_RECOMMENDED_AFTER = 100;
    private static final double VACUUM_THRESHOLD = 0.20;

    private static final List<String> STAT_TABLES = List.of(
            "files", "types", "members", "calls", "imports", "annotations", "type_relations",
            "type_deps", "config_entries", "gradle_modules", "gradle_deps", "java_modules",
            "module_directives", "index_errors");

    private IndexerApp() {
    }

    public static int run(IndexerOptions options, Log log) throws IOException {
        return run(options, log, new GitRunner());
    }

    public static int run(IndexerOptions options, Log log, GitRunner git) throws IOException {
        long start = System.nanoTime();
        log.info("Indexer " + Versions.indexer());
        log.info("root: " + options.root());
        log.info("out: " + options.out());
        log.info("languages: " + String.join(",", options.languages())
                + (options.noResolve() ? " (no symbol resolution)" : ""));

        SelectionResult selection = new FileSelector(options, log, git).select();
        long afterSelect = System.nanoTime();
        log.info("selection: " + (selection.viaGit() ? "git ls-files" : "directory walk")
                + ", selected=" + selection.files().size() + ", skipped=" + selection.skipped().size());

        List<Path> sourceRoots = SourceRoots.discover(options.root(), selection.files());
        for (Path sourceRoot : sourceRoots) {
            log.debug("java source root: " + sourceRoot);
        }
        int cores = Runtime.getRuntime().availableProcessors();
        int defaultWorkers = options.noResolve() ? Math.min(cores, MAX_PARSE_WORKERS)
                : Math.min(cores, DEFAULT_RESOLVE_WORKERS);
        int workers = Math.max(1, Integer.getInteger("indexer.threads", defaultWorkers));

        GitInfo gitInfo = GitInfo.collect(git, options.root(), options.out());
        String runTimestamp = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        LanguageRegistry registry = LanguageRegistry.create(options, sourceRoots);
        Path rootFileName = options.root().getFileName();
        String rootName = rootFileName == null ? "root" : rootFileName.toString();

        try (Db db = Db.open(options.out())) {
            boolean incremental = false;
            if (options.incremental()) {
                String blocker = incrementalBlocker(db, options);
                if (blocker == null) {
                    incremental = true;
                } else {
                    log.warn("incremental run is not possible (" + blocker + "); performing a full index");
                }
            }
            Map<String, PriorFile> prior = db.loadPriorFiles();
            long changesBefore = incremental ? parseLong(db.getMeta("changes_since_full")) : 0;

            if (incremental) {
                db.putMeta("notes", IN_PROGRESS);
            } else {
                db.wipeAllData();
                db.inTransaction(() -> {
                    db.putMeta("schema_version", String.valueOf(Schema.VERSION));
                    db.putMeta("notes", IN_PROGRESS);
                });
            }

            FileIndexPipeline.Result result;
            int removed = 0;
            try (IndexWriter writer = new IndexWriter(db, !options.noSource(), incremental)) {
                if (incremental) {
                    syncSkipped(db, writer, selection.skipped());
                } else {
                    writer.recordSkipped(selection.skipped());
                }
                result = FileIndexPipeline.run(selection.files(), registry, writer, prior, runTimestamp, log,
                        workers, incremental);
                if (incremental) {
                    Set<String> current = selection.files().stream().map(SelectedFile::path)
                            .collect(Collectors.toSet());
                    List<String> gone = prior.keySet().stream().filter(path -> !current.contains(path)).sorted()
                            .toList();
                    removed = writer.removeFiles(gone);
                }
                writer.finishGradle(rootName);
            }
            long afterWrite = System.nanoTime();

            if (!incremental) {
                db.rebuildFts();
            }
            long afterFts = System.nanoTime();

            long changes = result.written() + removed;
            long changesSinceFull = incremental ? changesBefore + changes : 0;
            writeFinalMeta(db, options, gitInfo, runTimestamp, rootName, incremental, changesSinceFull);
            boolean touchedMany = !prior.isEmpty() && (double) changes / prior.size() > VACUUM_THRESHOLD;
            db.setVacuumOnClose(!incremental || touchedMany);

            log.info("mode=" + (incremental ? "incremental" : "full") + ", written=" + result.written()
                    + ", unchanged=" + result.unchanged() + ", removed=" + removed + ", failed=" + result.failed()
                    + ", workers=" + workers);
            if (options.stats()) {
                printStats(db, log);
                log.info("timings: select=" + ms(start, afterSelect) + " ms, index+write="
                        + ms(afterSelect, afterWrite) + " ms, fts=" + ms(afterWrite, afterFts)
                        + " ms, total(before vacuum)=" + ms(start, System.nanoTime()) + " ms");
            }
        } catch (SQLException e) {
            throw new IOException("database error: " + e.getMessage(), e);
        }

        if (options.stats()) {
            log.info("db size: " + (Files.size(options.out()) / 1024) + " KB, total time: "
                    + ms(start, System.nanoTime()) + " ms");
        }
        return 0;
    }

    /** Returns why an incremental run cannot be trusted, or null when it can. */
    private static String incrementalBlocker(Db db, IndexerOptions options) throws SQLException {
        if (db.getMeta("indexed_at") == null) {
            return "no previous index";
        }
        if (IN_PROGRESS.equals(db.getMeta("notes"))) {
            return "the previous run did not finish";
        }
        String[][] expectations = {
                {"indexer_version", Versions.indexer()},
                {"languages", String.join(",", options.languages())},
                {"source_included", options.noSource() ? "0" : "1"},
                {"symbol_resolution", options.noResolve() ? "0" : "1"}};
        for (String[] expectation : expectations) {
            String stored = db.getMeta(expectation[0]);
            if (!expectation[1].equals(stored)) {
                return expectation[0] + " changed: '" + stored + "' -> '" + expectation[1] + "'";
            }
        }
        return null;
    }

    /** Replaces the file-less errors (skipped files) only when they differ, so a no-op run keeps all ids. */
    private static void syncSkipped(Db db, IndexWriter writer, List<SkippedFile> skipped) throws SQLException {
        List<String> existing = new ArrayList<>();
        try (Statement st = db.connection().createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT path, stage, message FROM index_errors WHERE file_id IS NULL ORDER BY id")) {
            while (rs.next()) {
                existing.add(rs.getString(1) + "\t" + rs.getString(2) + "\t" + rs.getString(3));
            }
        }
        List<String> desired = skipped.stream()
                .map(item -> item.path() + "\t" + item.stage() + "\t" + item.message()).toList();
        if (existing.equals(desired)) {
            return;
        }
        db.inTransaction(() -> db.execute("DELETE FROM index_errors WHERE file_id IS NULL"));
        writer.recordSkipped(skipped);
    }

    private static long parseLong(String value) {
        try {
            return value == null ? 0 : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long ms(long fromNanos, long toNanos) {
        return (toNanos - fromNanos) / 1_000_000;
    }

    private static void writeFinalMeta(Db db, IndexerOptions options, GitInfo git, String runTimestamp,
                                       String rootName, boolean incremental, long changesSinceFull)
            throws SQLException {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("schema_version", String.valueOf(Schema.VERSION));
        meta.put("indexer_version", Versions.indexer());
        meta.put("indexed_at", runTimestamp);
        meta.put("root_name", rootName);
        meta.put("languages", String.join(",", options.languages()));
        meta.put("git_commit", git.commit());
        meta.put("git_commit_short", git.commitShort());
        meta.put("git_branch", git.branch());
        meta.put("git_dirty", git.dirty() ? "1" : "0");
        meta.put("source_included", options.noSource() ? "0" : "1");
        meta.put("symbol_resolution", options.noResolve() ? "0" : "1");
        meta.put("file_count", String.valueOf(db.queryLong("SELECT COUNT(*) FROM files")));
        meta.put("type_count", String.valueOf(db.queryLong("SELECT COUNT(*) FROM types")));
        meta.put("member_count", String.valueOf(db.queryLong("SELECT COUNT(*) FROM members")));
        meta.put("error_count", String.valueOf(db.queryLong("SELECT COUNT(*) FROM index_errors")));
        meta.put("changes_since_full", String.valueOf(changesSinceFull));
        meta.put("notes", incremental ? incrementalNote(changesSinceFull) : "");
        db.inTransaction(() -> {
            for (Map.Entry<String, String> entry : meta.entrySet()) {
                db.putMeta(entry.getKey(), entry.getValue());
            }
        });
    }

    private static String incrementalNote(long changesSinceFull) {
        String base = "Incremental index: files that depend on changed files are not re-resolved, so "
                + "calls.callee_owner_fqn, calls.resolved and type_deps of those files may be stale.";
        if (changesSinceFull >= FULL_RUN_RECOMMENDED_AFTER) {
            return base + " Full run recommended now: " + changesSinceFull + " changes since the last full run.";
        }
        return base + " Changes since the last full run: " + changesSinceFull + " (full run recommended after "
                + FULL_RUN_RECOMMENDED_AFTER + ").";
    }

    private static void printStats(Db db, Log log) throws SQLException {
        log.info("--- stats ---");
        for (String table : STAT_TABLES) {
            log.info(table + ": " + db.queryLong("SELECT COUNT(*) FROM " + table));
        }
        try (Statement st = db.connection().createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT stage, COUNT(*) FROM index_errors GROUP BY stage ORDER BY stage")) {
            StringBuilder sb = new StringBuilder();
            while (rs.next()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(rs.getString(1)).append('=').append(rs.getLong(2));
            }
            log.info("errors by stage: " + (sb.length() == 0 ? "none" : sb));
        }
    }
}