package ru.mcs.indexer;

import ru.mcs.indexer.files.FileSelector;
import ru.mcs.indexer.files.GitRunner;
import ru.mcs.indexer.files.SelectedFile;
import ru.mcs.indexer.files.SelectionResult;
import ru.mcs.indexer.files.SkippedFile;
import ru.mcs.indexer.util.Log;
import ru.mcs.indexer.util.Versions;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

public final class IndexerApp {

    private IndexerApp() {
    }

    public static int run(IndexerOptions options, Log log) throws IOException {
        long started = System.nanoTime();
        log.info("Indexer " + Versions.indexer());
        log.info("root: " + options.root());
        log.info("out: " + options.out());
        log.info("languages: " + String.join(",", options.languages()));

        FileSelector selector = new FileSelector(options, log, new GitRunner());
        SelectionResult result = selector.select();

        Map<String, Integer> byStage = new TreeMap<>();
        for (SkippedFile skipped : result.skipped()) {
            byStage.merge(skipped.stage(), 1, Integer::sum);
            log.debug("skipped [" + skipped.stage() + "] " + skipped.path() + ": " + skipped.message());
        }
        for (SelectedFile file : result.files()) {
            log.debug("selected " + file.path() + " (" + file.sizeBytes() + " bytes)");
        }

        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        log.info("selection: " + (result.viaGit() ? "git ls-files" : "directory walk")
                + ", selected=" + result.files().size()
                + ", skipped=" + result.skipped().size() + " " + byStage
                + ", time=" + elapsedMs + " ms");
        return 0;
    }
}