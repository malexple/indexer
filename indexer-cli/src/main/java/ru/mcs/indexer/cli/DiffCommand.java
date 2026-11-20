package ru.mcs.indexer.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import ru.mcs.indexer.patch.PatchWriter;
import ru.mcs.indexer.util.Log;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

@Command(name = "diff", mixinStandardHelpOptions = true, sortOptions = false,
        description = "Builds a patch that turns the --base database into the --new one (same db_id, higher seq).")
public final class DiffCommand implements Callable<Integer> {

    @Option(names = "--base", required = true, paramLabel = "FILE", description = "Older database.")
    private Path base;

    @Option(names = "--new", required = true, paramLabel = "FILE", description = "Newer database.")
    private Path current;

    @Option(names = "--patch-dir", required = true, paramLabel = "DIR",
            description = "Directory for patch-<from>-<to>.sql.gz.")
    private Path patchDir;

    @Override
    public Integer call() {
        Log log = new Log(false);
        try {
            Optional<PatchWriter.Result> result = PatchWriter.write(base.toAbsolutePath().normalize(),
                    current.toAbsolutePath().normalize(), patchDir.toAbsolutePath().normalize());
            if (result.isEmpty()) {
                log.info("both databases are at the same seq: nothing to patch");
            } else {
                PatchWriter.Result r = result.get();
                log.info("patch: " + r.file() + " (seq " + r.fromSeq() + " -> " + r.toSeq() + ", -" + r.deletes()
                        + " +" + r.inserts() + " ~" + r.updates() + " rows, " + r.bytes() / 1024 + " KB)");
            }
            return 0;
        } catch (IllegalStateException e) {
            log.error(e.getMessage());
            return 1;
        } catch (Exception e) {
            log.error("diff failed: " + e.getMessage());
            return 2;
        }
    }
}