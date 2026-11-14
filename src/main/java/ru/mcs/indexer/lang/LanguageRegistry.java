package ru.mcs.indexer.lang;

import ru.mcs.indexer.IndexerOptions;
import ru.mcs.indexer.lang.java.JavaLanguageIndexer;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class LanguageRegistry {

    private final Map<String, LanguageIndexer> byExtension = new HashMap<>();

    public static LanguageRegistry create(IndexerOptions options) {
        LanguageRegistry registry = new LanguageRegistry();
        for (String language : options.languages()) {
            if (language.equals("java")) {
                registry.register(new JavaLanguageIndexer());
            }
        }
        return registry;
    }

    public void register(LanguageIndexer indexer) {
        for (String extension : indexer.extensions()) {
            byExtension.put(extension, indexer);
        }
    }

    public Optional<LanguageIndexer> forPath(String path) {
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return Optional.empty();
        }
        String extension = name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return Optional.ofNullable(byExtension.get(extension));
    }
}