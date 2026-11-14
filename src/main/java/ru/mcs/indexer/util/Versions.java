package ru.mcs.indexer.util;

import ru.mcs.indexer.IndexerMain;

public final class Versions {

    private Versions() {
    }

    public static String indexer() {
        Package pkg = IndexerMain.class.getPackage();
        String version = pkg == null ? null : pkg.getImplementationVersion();
        return version == null ? "dev" : version;
    }
}