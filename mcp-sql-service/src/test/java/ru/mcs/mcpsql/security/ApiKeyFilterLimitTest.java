package ru.mcs.mcpsql.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import ru.mcs.mcpsql.config.MultiUserProperties;
import ru.mcs.mcpsql.config.ServiceProperties;
import ru.mcs.mcpsql.users.IssuedKeys;
import ru.mcs.mcpsql.users.KeyService;
import ru.mcs.mcpsql.users.TtlKind;
import ru.mcs.mcpsql.users.UsageService;
import ru.mcs.mcpsql.users.UserDb;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyFilterLimitTest {

    @TempDir
    Path tmp;

    private int run(ApiKeyFilter filter, String method, String uri, String key) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.addHeader("Authorization", "Bearer " + key);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
        filter.doFilter(request, response, (rq, rs) -> seen.set((HttpServletRequest) rq));
        return seen.get() == null ? response.getStatus() : 200;
    }

    @Test
    void mcpPostsAreCountedAgainstTheDailyLimit() throws Exception {
        ServiceProperties properties = new ServiceProperties(tmp.resolve("data"), "r", "w", 8000, 3, 200, 50);
        MultiUserProperties multi = MultiUserProperties.of(true, 50, 5).withDaily(10, 300, 30, 2);
        UserDb db = new UserDb(tmp.resolve("system/user.db"));
        KeyService keys = new KeyService(db, multi);
        UsageService usage = new UsageService(db, multi);
        ApiKeyFilter filter = new ApiKeyFilter(properties, multi, Optional.of(keys), Optional.of(usage));
        IssuedKeys alice = keys.issue(1, TtlKind.D1, Instant.now());
        IssuedKeys bob = keys.issue(2, TtlKind.D1, Instant.now());

        assertThat(run(filter, "POST", "/mcp", alice.readKey())).isEqualTo(200);
        assertThat(run(filter, "POST", "/mcp/ws", alice.readKey())).isEqualTo(200);
        assertThat(run(filter, "POST", "/mcp", alice.readKey())).isEqualTo(429);
        assertThat(run(filter, "GET", "/mcp", alice.readKey())).isEqualTo(200);
        assertThat(run(filter, "GET", "/api/workspaces", alice.writeKey())).isEqualTo(200);
        assertThat(run(filter, "POST", "/mcp", bob.readKey())).isEqualTo(200);
        assertThat(usage.today(alice.userId(), Instant.now()).requests()).isEqualTo(3);
    }
}
