package ru.mcs.mcpsql.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import ru.mcs.mcpsql.config.MultiUserProperties;
import ru.mcs.mcpsql.config.ServiceProperties;
import ru.mcs.mcpsql.users.IssuedKeys;
import ru.mcs.mcpsql.users.KeyService;
import ru.mcs.mcpsql.users.Principal;
import ru.mcs.mcpsql.users.Scope;
import ru.mcs.mcpsql.users.TtlKind;
import ru.mcs.mcpsql.users.UserDb;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyFilterTest {

    @TempDir
    Path tmp;

    private ServiceProperties properties;

    private record Result(int status, HttpServletRequest seen) {
    }

    @BeforeEach
    void setUp() {
        properties = new ServiceProperties(tmp.resolve("data"), "r", "w", 8000, 3, 200, 50);
    }

    private Result run(ApiKeyFilter filter, String method, String uri, String authorization, String internal)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        if (internal != null) {
            request.addHeader("X-Internal-Key", internal);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
        filter.doFilter(request, response, (rq, rs) -> seen.set((HttpServletRequest) rq));
        return new Result(seen.get() == null ? response.getStatus() : 200, seen.get());
    }

    @Test
    void localModeKeepsTheTwoStaticKeys() throws Exception {
        ApiKeyFilter filter = new ApiKeyFilter(properties, MultiUserProperties.of(false, 50, 5), Optional.empty());

        assertThat(run(filter, "GET", "/api/workspaces", "Bearer w", null).status()).isEqualTo(200);
        assertThat(run(filter, "GET", "/api/workspaces", "Bearer r", null).status()).isEqualTo(401);
        assertThat(run(filter, "GET", "/health", null, null).status()).isEqualTo(200);
        assertThat(run(filter, "POST", "/mcp", null, null).status()).isEqualTo(401);
        assertThat(run(filter, "POST", "/internal/keys", "Bearer w", null).status()).isEqualTo(401);

        Result mcp = run(filter, "POST", "/mcp", "Bearer r", null);
        assertThat(mcp.status()).isEqualTo(200);
        Principal principal = ApiKeyFilter.principal(mcp.seen());
        assertThat(principal.scope()).isEqualTo(Scope.READ);
        assertThat(principal.userId()).isZero();
    }

    @Test
    void multiUserModeChecksTheUserDatabase() throws Exception {
        UserDb db = new UserDb(tmp.resolve("system/user.db"));
        MultiUserProperties multi = MultiUserProperties.of(true, 50, 5).withInternalKey("internal-secret");
        KeyService keys = new KeyService(db, multi);
        ApiKeyFilter filter = new ApiKeyFilter(properties, multi, Optional.of(keys));
        IssuedKeys issued = keys.issue(1001, TtlKind.D1, Instant.now());

        assertThat(run(filter, "GET", "/api/workspaces", "Bearer " + issued.readKey(), null).status()).isEqualTo(401);
        Result write = run(filter, "GET", "/api/workspaces", "Bearer " + issued.writeKey(), null);
        assertThat(write.status()).isEqualTo(200);
        assertThat(ApiKeyFilter.principal(write.seen()).userId()).isEqualTo(issued.userId());
        assertThat(ApiKeyFilter.principal(write.seen()).scope()).isEqualTo(Scope.WRITE);

        Result read = run(filter, "POST", "/mcp", "Bearer " + issued.readKey(), null);
        assertThat(read.status()).isEqualTo(200);
        assertThat(ApiKeyFilter.principal(read.seen()).scope()).isEqualTo(Scope.READ);
        assertThat(run(filter, "POST", "/mcp", "Bearer " + issued.writeKey(), null).status()).isEqualTo(200);

        assertThat(run(filter, "POST", "/mcp", "Bearer w", null).status()).isEqualTo(401);
        assertThat(run(filter, "POST", "/mcp", "Bearer " + issued.statsToken(), null).status()).isEqualTo(401);
        assertThat(run(filter, "POST", "/mcp", null, null).status()).isEqualTo(401);
    }

    @Test
    void expiredKeysAreRefusedAtOnce() throws Exception {
        UserDb db = new UserDb(tmp.resolve("system/user.db"));
        MultiUserProperties multi = MultiUserProperties.of(true, 50, 5);
        KeyService keys = new KeyService(db, multi);
        ApiKeyFilter filter = new ApiKeyFilter(properties, multi, Optional.of(keys));
        IssuedKeys old = keys.issue(5, TtlKind.D1, Instant.now().minus(Duration.ofDays(2)));

        assertThat(run(filter, "POST", "/mcp", "Bearer " + old.readKey(), null).status()).isEqualTo(401);
    }

    @Test
    void internalEndpointsNeedTheInternalKey() throws Exception {
        UserDb db = new UserDb(tmp.resolve("system/user.db"));
        MultiUserProperties multi = MultiUserProperties.of(true, 50, 5).withInternalKey("internal-secret");
        ApiKeyFilter filter = new ApiKeyFilter(properties, multi, Optional.of(new KeyService(db, multi)));

        assertThat(run(filter, "POST", "/internal/keys", null, "internal-secret").status()).isEqualTo(200);
        assertThat(run(filter, "POST", "/internal/keys", null, "wrong").status()).isEqualTo(401);
        assertThat(run(filter, "POST", "/internal/keys", null, null).status()).isEqualTo(401);
    }

    @Test
    void multiModeWithoutTheKeyServiceFailsAtStartup() throws IOException {
        MultiUserProperties multi = MultiUserProperties.of(true, 50, 5);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ApiKeyFilter(properties, multi, Optional.empty()))
                .isInstanceOf(IllegalStateException.class);
    }
}
