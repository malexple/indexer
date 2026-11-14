package ru.mcs.indexer;

import ru.mcs.indexer.db.Db;
import ru.mcs.indexer.db.IndexWriter;
import ru.mcs.indexer.db.PriorFile;
import ru.mcs.indexer.files.FileSelector;
import ru.mcs.indexer.files.GitInfo;
import ru.mcs.indexer.files.GitRunner;
import ru.mcs.indexer.files.SelectionResult;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IndexerApp {

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
        log.info("languages: " + String.join(",", options.languages()));
        if (options.incremental()) {
            log.warn("--incremental is not implemented yet (stage 6); performing a full index");
        }

        SelectionResult selection = new FileSelector(options, log, git).select();
        long afterSelect = System.nanoTime();
        log.info("selection: " + (selection.viaGit() ? "git ls-files" : "directory walk")
                + ", selected=" + selection.files().size() + ", skipped=" + selection.skipped().size());

        GitInfo gitInfo = GitInfo.collect(git, options.root(), options.out());
        String runTimestamp = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();

        try (Db db = Db.open(options.out())) {
            Map<String, PriorFile> prior = db.loadPriorFiles();
            db.wipeAllData();
            db.inTransaction(() -> {
                db.putMeta("schema_version", String.valueOf(ru.mcs.indexer.db.Schema.VERSION));
                db.putMeta("notes", "indexing in progress");
            });

            FileIndexPipeline.Result result;
            try (IndexWriter writer = new IndexWriter(db)) {
                writer.recordSkipped(selection.skipped());
                result = FileIndexPipeline.run(selection.files(), writer, !options.noSource(),
                        prior, runTimestamp, log);
            }
            long afterWrite = System.nanoTime();

            db.rebuildFts();
            long afterFts = System.nanoTime();

            writeFinalMeta(db, options, gitInfo, runTimestamp);
            db.setVacuumOnClose(true);

            log.info("written=" + result.written() + ", failed=" + result.failed());
            if (options.stats()) {
                printStats(db, log);
                log.info("timings: select=" + ms(start, afterSelect)
                        + " ms, write=" + ms(afterSelect, afterWrite)
                        + " ms, fts=" + ms(afterWrite, afterFts)
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

    private static long ms(long fromNanos, long toNanos) {
        return (toNanos - fromNanos) / 1_000_000;
    }

    private static void writeFinalMeta(Db db, IndexerOptions options, GitInfo git, String runTimestamp)
            throws SQLException {
        Path name = options.root().getFileName();
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("schema_version", String.valueOf(ru.mcs.indexer.db.Schema.VERSION));
        meta.put("indexer_version", Versions.indexer());
        meta.put("indexed_at", runTimestamp);
        meta.put("root_name", name == null ? options.root().toString() : name.toString());
        meta.put("languages", String.join(",", options.languages()));
        meta.put("git_commit", git.commit());
        meta.put("git_commit_short", git.commitShort());
        meta.put("git_branch", git.branch());
        meta.put("git_dirty", git.dirty() ? "1" : "0");
        meta.put("source_included", options.noSource() ? "0" : "1");
        meta.put("file_count", String.valueOf(db.queryLong("SELECT COUNT(*) FROM files")));
        meta.put("type_count", String.valueOf(db.queryLong("SELECT COUNT(*) FROM types")));
        meta.put("member_count", String.valueOf(db.queryLong("SELECT COUNT(*) FROM members")));
        meta.put("error_count", String.valueOf(db.queryLong("SELECT COUNT(*) FROM index_errors")));
        meta.put("notes", "");
        db.inTransaction(() -> {
            for (Map.Entry<String, String> entry : meta.entrySet()) {
                db.putMeta(entry.getKey(), entry.getValue());
            }
        });
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