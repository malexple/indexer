package ru.mcs.indexer.files;

import java.util.List;

public record SelectionResult(List<SelectedFile> files, List<SkippedFile> skipped, boolean viaGit) {
}