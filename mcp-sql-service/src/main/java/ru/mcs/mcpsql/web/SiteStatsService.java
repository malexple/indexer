package ru.mcs.mcpsql.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.mcs.mcpsql.config.MultiUserProperties;
import ru.mcs.mcpsql.users.KeyService;
import ru.mcs.mcpsql.users.PublicStats;
import ru.mcs.mcpsql.users.UsageService;
import ru.mcs.mcpsql.workspace.WorkspaceService;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Aggregate numbers for the public site: no user ids, no keys. The answer is kept for a few seconds. */
@Service
@ConditionalOnProperty(name = "mcpsql.multi.enabled", havingValue = "true")
public class SiteStatsService {

    private static final Duration FRESH = Duration.ofSeconds(15);

    private final KeyService keys;
    private final UsageService usage;
    private final WorkspaceService workspaces;
    private final MultiUserProperties properties;
    private Map<String, Object> cached;
    private Instant cachedAt;

    public SiteStatsService(KeyService keys, UsageService usage, WorkspaceService workspaces,
                            MultiUserProperties properties) {
        this.keys = keys;
        this.usage = usage;
        this.workspaces = workspaces;
        this.properties = properties;
    }

    public synchronized Map<String, Object> get(Instant now) throws IOException {
        if (cached != null && now.isBefore(cachedAt.plus(FRESH))) {
            return cached;
        }
        PublicStats stats = keys.stats(now);
        Map<String, Object> users = new LinkedHashMap<>();
        users.put("registered", stats.registered());
        users.put("active", stats.activeUsers());
        users.put("capacity", properties.maxActiveUsers());
        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("usedBytes", workspaces.usedBytes());
        storage.put("freeBytes", workspaces.freeBytes());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("users", users);
        body.put("pairs", stats.activePairs());
        body.put("storage", storage);
        body.put("limits", usage.limits());
        body.put("generatedAt", now.toString());
        cached = body;
        cachedAt = now;
        return body;
    }
}
