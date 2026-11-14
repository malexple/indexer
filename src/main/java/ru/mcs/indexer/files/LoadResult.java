package ru.mcs.indexer.files;

import ru.mcs.indexer.lang.FileFacts;

public sealed interface LoadResult {

    record Loaded(LoadedFile file, FileFacts facts) implements LoadResult {

        public Loaded(LoadedFile file) {
            this(file, null);
        }
    }

    record Failed(String path, String message) implements LoadResult {
    }
}