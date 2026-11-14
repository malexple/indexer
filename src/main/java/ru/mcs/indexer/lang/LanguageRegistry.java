package ru.mcs.indexer.lang;

import ru.mcs.indexer.IndexerOptions;
import ru.mcs.indexer.lang.config.ConfigLanguageIndexer;
import ru.mcs.indexer.lang.gradle.GradleLanguageIndexer;
import ru.mcs.indexer.lang.java.JavaLanguageIndexer;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class LanguageRegistry {

    private final Map<String, LanguageIndexer> byExtension = new HashMap<>();

    /** Structural indexers (Gradle, configs) are always on; AST indexers follow --lang. */
    public static LanguageRegistry create(IndexerOptions options, List<Path> javaSourceRoots) {
        LanguageRegistry registry = new LanguageRegistry();
        registry.register(new GradleLanguageIndexer());
        registry.register(new ConfigLanguageIndexer());
        for (String language : options.languages()) {
            if (language.equals("java")) {
                registry.register(new JavaLanguageIndexer(javaSourceRoots, !options.noResolve()));
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
        return Optional.ofNullable(byExtension.get(name.substring(dot + 1).toLowerCase(Locale.ROOT)));
    }
}