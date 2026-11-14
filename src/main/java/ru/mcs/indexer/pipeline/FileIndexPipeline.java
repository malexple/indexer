package ru.mcs.indexer.pipeline;

import ru.mcs.indexer.db.IndexWriter;
import ru.mcs.indexer.db.PriorFile;
import ru.mcs.indexer.files.FileLoader;
import ru.mcs.indexer.files.LoadResult;
import ru.mcs.indexer.files.LoadedFile;
import ru.mcs.indexer.files.SelectedFile;
import ru.mcs.indexer.util.Log;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class FileIndexPipeline {

    public record Result(int written, int failed) {
    }

    private FileIndexPipeline() {
    }

    /**
     * Files are loaded in a thread pool, but results are written strictly in input order by the
     * calling thread (the single writer). This keeps row ids deterministic between runs.
     */
    public static Result run(List<SelectedFile> files, IndexWriter writer, boolean keepContent,
                             Map<String, PriorFile> prior, String runTimestamp, Log log)
            throws SQLException {
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        int window = threads * 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "indexer-load");
            thread.setDaemon(true);
            return thread;
        });
        Deque<Future<LoadResult>> pending = new ArrayDeque<>();
        int written = 0;
        int failed = 0;
        try {
            for (SelectedFile file : files) {
                pending.addLast(pool.submit(() -> FileLoader.load(file, keepContent)));
                if (pending.size() >= window) {
                    if (consume(pending.removeFirst(), writer, prior, runTimestamp, log)) {
                        written++;
                    } else {
                        failed++;
                    }
                }
            }
            while (!pending.isEmpty()) {
                if (consume(pending.removeFirst(), writer, prior, runTimestamp, log)) {
                    written++;
                } else {
                    failed++;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return new Result(written, failed);
    }

    private static boolean consume(Future<LoadResult> future, IndexWriter writer,
                                   Map<String, PriorFile> prior, String runTimestamp, Log log)
            throws SQLException {
        LoadResult result;
        try {
            result = future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while loading files", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("unexpected loader failure", e.getCause());
        }
        return switch (result) {
            case LoadResult.Loaded loaded -> {
                LoadedFile file = loaded.file();
                boolean ok = writer.writeFile(file, indexedAt(file, prior, runTimestamp));
                if (!ok) {
                    log.warn("DB error while writing " + file.path() + " (see index_errors)");
                }
                yield ok;
            }
            case LoadResult.Failed failure -> {
                writer.recordError(null, failure.path(), "READ", failure.message());
                log.warn("cannot read " + failure.path() + ": " + failure.message());
                yield false;
            }
        };
    }

    private static String indexedAt(LoadedFile file, Map<String, PriorFile> prior, String runTimestamp) {
        PriorFile previous = prior.get(file.path());
        if (previous != null && previous.sha256().equals(file.sha256())) {
            return previous.indexedAt();
        }
        return runTimestamp;
    }
}