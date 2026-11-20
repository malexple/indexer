package ru.mcs.indexer.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import ru.mcs.indexer.patch.PatchApplier;
import ru.mcs.indexer.util.Log;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "apply", mixinStandardHelpOptions = true, sortOptions = false,
        description = "Applies a patch (made with --patch-dir) to an index database, all or nothing.")
public final class ApplyCommand implements Callable<Integer> {

    @Option(names = {"-d", "--db"}, paramLabel = "FILE",
            description = "Database to patch (default: .index/index.db in the current directory).")
    private Path db;

    @Parameters(index = "0", paramLabel = "PATCH", description = "Patch file (.sql or .sql.gz).")
    private Path patch;

    @Override
    public Integer call() {
        Log log = new Log(false);
        Path target = (db == null ? Path.of(".index", "index.db") : db).toAbsolutePath().normalize();
        try {
            PatchApplier.Result result = PatchApplier.apply(target, patch.toAbsolutePath().normalize());
            log.info("applied: seq " + result.fromSeq() + " -> " + result.toSeq() + " (" + target + ")");
            return 0;
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.error(e.getMessage());
            return 1;
        } catch (Exception e) {
            log.error("patch failed, database left unchanged: " + e.getMessage());
            return 2;
        }
    }
}