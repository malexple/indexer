package ru.mcs.indexer;

import ru.mcs.indexer.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Copies the bundled QUERIES.md next to the database (only when the content differs). */
public final class QueriesDoc {

    private QueriesDoc() {
    }

    public static void copyNextTo(Path database, Log log) {
        try (InputStream in = QueriesDoc.class.getResourceAsStream("/QUERIES.md")) {
            if (in == null) {
                log.debug("QUERIES.md is not bundled; skipping copy");
                return;
            }
            byte[] content = in.readAllBytes();
            Path directory = database.toAbsolutePath().getParent();
            if (directory == null) {
                return;
            }
            Path target = directory.resolve("QUERIES.md");
            if (Files.exists(target) && Arrays.equals(Files.readAllBytes(target), content)) {
                return;
            }
            Files.write(target, content);
        } catch (IOException e) {
            log.warn("cannot copy QUERIES.md next to the database: " + e.getMessage());
        }
    }
}