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
        @DefaultValue("") String internalKey,
        @DefaultValue("10") int uploadsPerDay,
        @DefaultValue("300") int writeMbPerDay,
        @DefaultValue("30") int patchIntervalSeconds,
        @DefaultValue("5000") int requestsPerDay,
        @DefaultValue("2048") int minFreeDiskMb) {

    public static MultiUserProperties of(boolean enabled, int maxActiveUsers, int keyIssuesPerDay) {
        return new MultiUserProperties(enabled, maxActiveUsers, keyIssuesPerDay, 3, 20, "", 10, 300, 30, 5000, 2048);
    }

    public MultiUserProperties withLimits(int maxWorkspaces, int maxDbMb) {
        return new MultiUserProperties(enabled, maxActiveUsers, keyIssuesPerDay, maxWorkspaces, maxDbMb, internalKey,
                uploadsPerDay, writeMbPerDay, patchIntervalSeconds, requestsPerDay, minFreeDiskMb);
    }

    public MultiUserProperties withInternalKey(String key) {
        return new MultiUserProperties(enabled, maxActiveUsers, keyIssuesPerDay, maxWorkspaces, maxDbMb, key,
                uploadsPerDay, writeMbPerDay, patchIntervalSeconds, requestsPerDay, minFreeDiskMb);
    }

    public MultiUserProperties withDaily(int uploadsPerDay, int writeMbPerDay, int patchIntervalSeconds,
                                         int requestsPerDay) {
        return new MultiUserProperties(enabled, maxActiveUsers, keyIssuesPerDay, maxWorkspaces, maxDbMb, internalKey,
                uploadsPerDay, writeMbPerDay, patchIntervalSeconds, requestsPerDay, minFreeDiskMb);
    }

    public MultiUserProperties withMinFreeDiskMb(int minFreeDiskMb) {
        return new MultiUserProperties(enabled, maxActiveUsers, keyIssuesPerDay, maxWorkspaces, maxDbMb, internalKey,
                uploadsPerDay, writeMbPerDay, patchIntervalSeconds, requestsPerDay, minFreeDiskMb);
    }
}
