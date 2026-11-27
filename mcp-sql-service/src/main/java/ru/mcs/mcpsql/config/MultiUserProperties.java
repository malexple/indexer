package ru.mcs.mcpsql.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Multi-user mode; off by default, so a plain local run keeps the static read and write keys. */
@ConfigurationProperties("mcpsql.multi")
public record MultiUserProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("50") int maxActiveUsers,
        @DefaultValue("5") int keyIssuesPerDay,
        @DefaultValue("3") int maxWorkspaces,
        @DefaultValue("20") int maxDbMb,
        @DefaultValue("") String internalKey) {

    public static MultiUserProperties of(boolean enabled, int maxActiveUsers, int keyIssuesPerDay) {
        return new MultiUserProperties(enabled, maxActiveUsers, keyIssuesPerDay, 3, 20, "");
    }

    public MultiUserProperties withLimits(int maxWorkspaces, int maxDbMb) {
        return new MultiUserProperties(enabled, maxActiveUsers, keyIssuesPerDay, maxWorkspaces, maxDbMb, internalKey);
    }

    public MultiUserProperties withInternalKey(String key) {
        return new MultiUserProperties(enabled, maxActiveUsers, keyIssuesPerDay, maxWorkspaces, maxDbMb, key);
    }
}
