package ru.mcs.indexer.lang.config;

import ru.mcs.indexer.lang.FileContext;
import ru.mcs.indexer.lang.FileFacts;
import ru.mcs.indexer.lang.FileFacts.ConfigRow;
import ru.mcs.indexer.lang.LanguageIndexer;

import java.util.Locale;
import java.util.Set;

/** Flattens yml/yaml/json/properties/toml files into config_entries rows. Stateless, thread-safe. */
public final class ConfigLanguageIndexer implements LanguageIndexer {

    static final int MAX_ENTRIES = 5000;
    static final int MAX_VALUE = 2000;

    /** Receives flattened entries and enforces the per-file limits. */
    static final class Sink {
        private final FileFacts out;
        private int count;
        private boolean truncated;

        Sink(FileFacts out) {
            this.out = out;
        }

        boolean full() {
            return count >= MAX_ENTRIES;
        }

        void add(String keyPath, String value, int line) {
            if (full()) {
                truncated = true;
                return;
            }
            String stored = value;
            if (stored != null && stored.length() > MAX_VALUE) {
                stored = stored.substring(0, MAX_VALUE);
            }
            out.addConfig(new ConfigRow(keyPath, stored, line));
            count++;
        }

        boolean truncated() {
            return truncated;
        }
    }

    @Override
    public String language() {
        return "config";
    }

    @Override
    public Set<String> extensions() {
        return Set.of("yml", "yaml", "json", "properties", "toml");
    }

    @Override
    public void index(FileContext ctx, FileFacts out) {
        String path = ctx.path();
        String extension = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        Sink sink = new Sink(out);
        try {
            switch (extension) {
                case "properties" -> PropertiesParser.parse(ctx.content(), sink);
                case "yml", "yaml", "json" -> YamlParser.parse(ctx.content(), sink);
                case "toml" -> TomlParser.parse(ctx.content(), sink);
                default -> {
                }
            }
        } catch (RuntimeException | StackOverflowError e) {
            String message = String.valueOf(e).replaceAll("\\s+", " ");
            out.addError("PARSE", "config parse failed: "
                    + (message.length() > 300 ? message.substring(0, 300) : message));
        }
        if (sink.truncated()) {
            out.addError("PARSE", "config entries truncated at " + MAX_ENTRIES);
        }
    }
}