package ru.mcs.indexer.files;

import java.nio.file.Path;

public record SelectedFile(String path, Path absolutePath, long sizeBytes) {
}