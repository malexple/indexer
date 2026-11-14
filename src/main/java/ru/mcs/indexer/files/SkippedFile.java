package ru.mcs.indexer.files;

public record SkippedFile(String path, String stage, String message) {
}