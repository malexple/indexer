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

    public record Result(int written, int failed, int unchanged) {
    }

    private static final int WRITTEN = 0;
    private static final int FAILED = 1;
    private static final int UNCHANGED = 2;

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
     * Workers read, hash and parse files; the calling thread is the single DB writer and consumes results
     * strictly in input order, which keeps row ids deterministic between runs. In incremental mode a file
     * whose SHA-256 equals the stored one is not parsed (except Gradle scripts, which feed the module model).
     * Profiling: -Dindexer.profile=true (with -v).
     */
    public static Result run(List<SelectedFile> files, LanguageRegistry registry, IndexWriter writer,
                             Map<String, PriorFile> prior, String runTimestamp, Log log, int threads,
                             boolean incremental) throws SQLException {
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
        int[] counts = new int[3];
        try {
            for (SelectedFile file : files) {
                pending.addLast(pool.submit(() -> process(file, registry, prior, incremental, timings)));
                if (pending.size() >= window) {
                    counts[consume(pending.removeFirst(), writer, prior, runTimestamp, log, incremental)]++;
                }
            }
            while (!pending.isEmpty()) {
                counts[consume(pending.removeFirst(), writer, prior, runTimestamp, log, incremental)]++;
            }
        } finally {
            pool.shutdownNow();
            if (sampler != null) {
                sampler.close();
            }
        }
        reportTimings(timings, workers, log);
        return new Result(counts[WRITTEN], counts[FAILED], counts[UNCHANGED]);
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

    private static LoadResult process(SelectedFile file, LanguageRegistry registry, Map<String, PriorFile> prior,
                                      boolean incremental, Timings timings) {
        LoadResult result = FileLoader.load(file, true);
        if (!(result instanceof LoadResult.Loaded loaded)) {
            return result;
        }
        LoadedFile data = loaded.file();
        Optional<LanguageIndexer> indexer = registry.forPath(file.path());
        if (incremental) {
            PriorFile previous = prior.get(data.path());
            if (previous != null && previous.sha256().equals(data.sha256())) {
                FileFacts gradleFacts = null;
                if (data.lang().equals("gradle") && indexer.isPresent()) {
                    gradleFacts = new FileFacts();
                    runIndexer(indexer.get(), data, gradleFacts, timings);
                }
                return new LoadResult.Unchanged(data, gradleFacts);
            }
        }
        if (indexer.isEmpty()) {
            return result;
        }
        FileFacts facts = new FileFacts();
        runIndexer(indexer.get(), data, facts, timings);
        return new LoadResult.Loaded(data, facts);
    }

    private static void runIndexer(LanguageIndexer indexer, LoadedFile data, FileFacts facts, Timings timings) {
        long started = System.nanoTime();
        try {
            indexer.index(new FileContext(data.path(), data.content(), data.kind()), facts);
        } catch (RuntimeException | StackOverflowError e) {
            facts.addError("PARSE", "indexer failed: " + e);
        }
        timings.add(data.path(), System.nanoTime() - started);
    }

    private static int consume(Future<LoadResult> future, IndexWriter writer, Map<String, PriorFile> prior,
                               String runTimestamp, Log log, boolean incremental) throws SQLException {
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
                yield ok ? WRITTEN : FAILED;
            }
            case LoadResult.Unchanged unchanged -> {
                writer.collectGradle(unchanged.file().path(), unchanged.facts());
                yield UNCHANGED;
            }
            case LoadResult.Failed failure -> {
                if (incremental) {
                    writer.removeFiles(List.of(failure.path()));
                }
                writer.recordError(null, failure.path(), "READ", failure.message());
                log.warn("cannot read " + failure.path() + ": " + failure.message());
                yield FAILED;
            }
        };
    }

    private static String indexedAt(LoadedFile file, Map<String, PriorFile> prior, String runTimestamp) {
        PriorFile previous = prior.get(file.path());
        return previous != null && previous.sha256().equals(file.sha256()) ? previous.indexedAt() : runTimestamp;
    }
}