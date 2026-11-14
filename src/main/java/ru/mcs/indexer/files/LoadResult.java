package ru.mcs.indexer.files;

public sealed interface LoadResult {

    record Loaded(LoadedFile file) implements LoadResult {
    }

    record Failed(String path, String message) implements LoadResult {
    }
}