package ru.mcs.indexer.util;


public final class Versions {

    private Versions() {
    }

    public static String indexer() {
        Package pkg = Versions.class.getPackage();
        String version = pkg == null ? null : pkg.getImplementationVersion();
        return version == null ? "dev" : version;
    }
}