package ru.mcs.mcpsql.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import ru.mcs.mcpsql.config.MultiUserProperties;
import ru.mcs.mcpsql.config.ServiceProperties;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyFilterPublicTest {

    @TempDir
    Path tmp;

    @Test
    void publicStatisticsNeedNoKeyButEverythingElseStillDoes() throws Exception {
        ServiceProperties properties = new ServiceProperties(tmp.resolve("data"), "r", "w", 8000, 3, 200, 50);
        ApiKeyFilter filter = new ApiKeyFilter(properties, MultiUserProperties.of(false, 50, 5), Optional.empty());

        assertThat(status(filter, "/public/stats")).isEqualTo(200);
        assertThat(status(filter, "/health")).isEqualTo(200);
        assertThat(status(filter, "/api/workspaces")).isEqualTo(401);
        assertThat(status(filter, "/mcp")).isEqualTo(401);
        assertThat(status(filter, "/internal/stats")).isEqualTo(401);
    }

    private static int status(ApiKeyFilter filter, String uri) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
        filter.doFilter(new MockHttpServletRequest("GET", uri), response, (rq, rs) -> seen.set((HttpServletRequest) rq));
        return seen.get() == null ? response.getStatus() : 200;
    }
}
