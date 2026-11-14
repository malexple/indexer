package ru.mcs.indexer.lang;

import java.util.Set;

/**
 * Parses one file into an in-memory buffer. Implementations must be thread-safe:
 * index() is called concurrently from the worker pool. Nothing is written to the database here.
 */
public interface LanguageIndexer {

    String language();

    Set<String> extensions();

    void index(FileContext ctx, FileFacts out);
}