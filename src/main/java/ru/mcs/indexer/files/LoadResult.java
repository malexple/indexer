package ru.mcs.indexer.files;

import ru.mcs.indexer.lang.FileFacts;

public sealed interface LoadResult {

    /** New or changed file, to be written. */
    record Loaded(LoadedFile file, FileFacts facts) implements LoadResult {

        public Loaded(LoadedFile file) {
            this(file, null);
        }
    }

    /** Incremental mode: same SHA-256 as in the database. Facts are present only for Gradle files. */
    record Unchanged(LoadedFile file, FileFacts facts) implements LoadResult {
    }

    record Failed(String path, String message) implements LoadResult {
    }
}