package ru.mcs.indexer.cli;

import picocli.CommandLine.IVersionProvider;
import ru.mcs.indexer.util.Versions;

public final class VersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
        return new String[]{"Indexer " + Versions.indexer()};
    }
}