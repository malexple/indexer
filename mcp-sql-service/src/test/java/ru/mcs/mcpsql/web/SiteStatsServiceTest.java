package ru.mcs.mcpsql.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.mcs.mcpsql.config.MultiUserProperties;
import ru.mcs.mcpsql.config.ServiceProperties;
import ru.mcs.mcpsql.users.KeyService;
import ru.mcs.mcpsql.users.TtlKind;
import ru.mcs.mcpsql.users.UsageService;
import ru.mcs.mcpsql.users.UserDb;
import ru.mcs.mcpsql.workspace.WorkspaceService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SiteStatsServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-27T10:00:00Z");

    @TempDir
    Path tmp;

    private KeyService keys;
    private WorkspaceService workspaces;
    private SiteStatsService site;

    @BeforeEach
    void setUp() throws Exception {
        MultiUserProperties multi = MultiUserProperties.of(true, 40, 5).withMinFreeDiskMb(0);
        ServiceProperties properties = new ServiceProperties(tmp.resolve("data"), "r", "w", 8000, 3, 200, 50);
        UserDb db = new UserDb(tmp.resolve("data/system/user.db"));
        keys = new KeyService(db, multi);
        workspaces = new WorkspaceService(properties, multi);
        site = new SiteStatsService(keys, new UsageService(db, multi), workspaces, multi);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @Test
    void theNumbersAreAggregateAndCarryNoIdentities() throws Exception {
        keys.issue(1001, TtlKind.D1, T0);
        long second = keys.issue(1002, TtlKind.H5, T0).userId();
        Path file = workspaces.dbPath(second + "/a");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[1000]);

        Map<String, Object> body = site.get(T0);

        assertThat(map(body.get("users"))).containsEntry("registered", 2L).containsEntry("active", 2L)
                .containsEntry("capacity", 40);
        assertThat(map(body.get("pairs"))).containsEntry("5h", 1L).containsEntry("1d", 1L).containsEntry("7d", 0L);
        assertThat(((Number) map(body.get("storage")).get("usedBytes")).longValue()).isEqualTo(1000);
        assertThat(((Number) map(body.get("storage")).get("freeBytes")).longValue()).isPositive();
        assertThat(map(body.get("limits"))).containsKeys("maxWorkspaces", "maxDbBytes", "requestsPerDay");
        assertThat(body.get("generatedAt")).isEqualTo(T0.toString());
        assertThat(body.keySet()).containsExactly("users", "pairs", "storage", "limits", "generatedAt");
        assertThat(map(body.get("users")).keySet()).containsExactly("registered", "active", "capacity");
        assertThat(body.toString()).doesNotContain("mcp_");
    }

    @Test
    void theAnswerIsKeptForAFewSeconds() throws Exception {
        keys.issue(1, TtlKind.D1, T0);
        assertThat(map(site.get(T0).get("users"))).containsEntry("registered", 1L);

        keys.issue(2, TtlKind.D1, T0);
        assertThat(map(site.get(T0.plusSeconds(5)).get("users"))).containsEntry("registered", 1L);
        assertThat(map(site.get(T0.plusSeconds(16)).get("users"))).containsEntry("registered", 2L);
    }
}
