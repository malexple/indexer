package ru.mcs.indexer;

import ru.mcs.indexer.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Copies the bundled QUERIES.md next to the database (only when the content differs). */
public final class QueriesDoc {
    private static final List<String> RESOURCES = List.of("QUERIES.md", "apply_patch.py");

    private QueriesDoc() {
    }

    public static void copyNextTo(Path database, Log log) {
        Path directory = database.toAbsolutePath().getParent();
        if (directory == null) {
            return;
        }
        for (String name : RESOURCES) {
            copy(name, directory, log);
        }
    }

    private static void copy(String name, Path directory, Log log) {
        try (InputStream in = QueriesDoc.class.getResourceAsStream("/" + name)) {
            if (in == null) {
                log.debug(name + " is not bundled; skipping copy");
                return;
            }
            byte[] content = in.readAllBytes();
            Path target = directory.resolve(name);
            if (Files.exists(target) && Arrays.equals(Files.readAllBytes(target), content)) {
                return;
            }
            Files.write(target, content);
        } catch (IOException e) {
            log.warn("cannot copy " + name + " next to the database: " + e.getMessage());
        }
    }
}