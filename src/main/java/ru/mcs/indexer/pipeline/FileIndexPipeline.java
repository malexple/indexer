package ru.mcs.indexer.pipeline;

import ru.mcs.indexer.db.IndexWriter;
import ru.mcs.indexer.db.PriorFile;
import ru.mcs.indexer.files.FileLoader;
import ru.mcs.indexer.files.LoadResult;
import ru.mcs.indexer.files.LoadedFile;
import ru.mcs.indexer.files.SelectedFile;
import ru.mcs.indexer.lang.FileContext;
import ru.mcs.indexer.lang.FileFacts;
import ru.mcs.indexer.lang.LanguageIndexer;
import ru.mcs.indexer.lang.LanguageRegistry;
import ru.mcs.indexer.util.Log;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.LongAdder;

public final class FileIndexPipeline {

    public record Result(int written, int failed) {
    }

    private static final class Timings {
        final LongAdder totalNanos = new LongAdder();
        final List<Map.Entry<String, Long>> samples = Collections.synchronizedList(new ArrayList<>());

        void add(String path, long nanos) {
            totalNanos.add(nanos);
            samples.add(Map.entry(path, nanos));
        }
    }

    private FileIndexPipeline() {
    }

    /**
     * Workers read and parse files; the calling thread is the single DB writer and consumes results
     * strictly in input order, which keeps row ids deterministic between runs.
     * Profiling: -Dindexer.profile=true (with -v).
     */
    public static Result run(List<SelectedFile> files, LanguageRegistry registry, IndexWriter writer,
                             Map<String, PriorFile> prior, String runTimestamp, Log log, int threads)
            throws SQLException {
        int workers = Math.max(1, threads);
        int window = workers * 8;
        Timings timings = new Timings();
        ThreadSampler sampler = Boolean.getBoolean("indexer.profile")
                ? new ThreadSampler("indexer-worker", log) : null;
        ExecutorService pool = Executors.newFixedThreadPool(workers, runnable -> {
            Thread thread = new Thread(runnable, "indexer-worker");
            thread.setDaemon(true);
            return thread;
        });
        Deque<Future<LoadResult>> pending = new ArrayDeque<>();
        int written = 0;
        int failed = 0;
        try {
            for (SelectedFile file : files) {
                pending.addLast(pool.submit(() -> process(file, registry, timings)));
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
            if (sampler != null) {
                sampler.close();
            }
        }
        reportTimings(timings, workers, log);
        return new Result(written, failed);
    }

    private static void reportTimings(Timings timings, int threads, Log log) {
        log.debug("[perf] workers=" + threads + ", parse+resolve time summed over workers="
                + timings.totalNanos.sum() / 1_000_000 + " ms, files=" + timings.samples.size());
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(timings.samples);
        sorted.sort(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()));
        for (int i = 0; i < Math.min(5, sorted.size()); i++) {
            log.debug("[perf] slow " + sorted.get(i).getValue() / 1_000_000 + " ms " + sorted.get(i).getKey());
        }
    }

    private static LoadResult process(SelectedFile file, LanguageRegistry registry, Timings timings) {
        LoadResult result = FileLoader.load(file, true);
        if (!(result instanceof LoadResult.Loaded loaded)) {
            return result;
        }
        Optional<LanguageIndexer> indexer = registry.forPath(file.path());
        if (indexer.isEmpty()) {
            return result;
        }
        FileFacts facts = new FileFacts();
        LoadedFile data = loaded.file();
        long started = System.nanoTime();
        try {
            indexer.get().index(new FileContext(data.path(), data.content(), data.kind()), facts);
        } catch (RuntimeException | StackOverflowError e) {
            facts.addError("PARSE", "indexer failed: " + e);
        }
        timings.add(data.path(), System.nanoTime() - started);
        return new LoadResult.Loaded(data, facts);
    }

    private static boolean consume(Future<LoadResult> future, IndexWriter writer, Map<String, PriorFile> prior,
                                   String runTimestamp, Log log) throws SQLException {
        LoadResult result;
        try {
            result = future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while loading files", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("unexpected worker failure", e.getCause());
        }
        return switch (result) {
            case LoadResult.Loaded loaded -> {
                LoadedFile file = loaded.file();
                boolean ok = writer.writeFile(file, loaded.facts(), indexedAt(file, prior, runTimestamp));
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
        return previous != null && previous.sha256().equals(file.sha256()) ? previous.indexedAt() : runTimestamp;
    }
}