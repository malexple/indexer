package ru.mcs.indexer.files;

import java.util.List;

public record LoadedFile(
        String path,
        String lang,
        String kind,
        long sizeBytes,
        int lineCount,
        String sha256,
        String content,
        List<String> warnings) {
}