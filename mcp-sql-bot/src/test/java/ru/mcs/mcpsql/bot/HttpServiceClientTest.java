package ru.mcs.mcpsql.bot;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpServiceClientTest {

    private HttpServer server;
    private HttpServiceClient client;
    private final AtomicReference<String> seenKey = new AtomicReference<>();
    private final AtomicReference<String> seenRequest = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String answer = "{}";

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenKey.set(exchange.getRequestHeaders().getFirst("X-Internal-Key"));
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seenRequest.set(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " " + requestBody);
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        BotProperties properties = new BotProperties("t", "http://127.0.0.1:" + server.getAddress().getPort(),
                "internal-secret", "", "http://unused", 50);
        client = new HttpServiceClient(properties, JsonMapper.builder().build(), HttpClient.newHttpClient());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void keysAreRequestedWithTheInternalKey() throws Exception {
        answer = "{\"readKey\":\"mcp_r_a\",\"writeKey\":\"mcp_w_b\",\"statsToken\":\"mcp_s_c\","
                + "\"ttl\":\"1d\",\"expiresAt\":\"2026-11-28T10:00:00Z\"}";

        ServiceClient.IssuedKeys keys = client.issueKeys(1001, "1d");

        assertThat(seenKey.get()).isEqualTo("internal-secret");
        assertThat(seenRequest.get()).startsWith("POST /internal/keys ").contains("\"tgId\":1001").contains("\"ttl\":\"1d\"");
        assertThat(keys.readKey()).isEqualTo("mcp_r_a");
        assertThat(keys.writeKey()).isEqualTo("mcp_w_b");
        assertThat(keys.expiresAt()).isEqualTo("2026-11-28T10:00:00Z");
    }

    @Test
    void refusalsKeepTheirCode() {
        status = 429;
        answer = "{\"code\":\"KEY_ISSUE_LIMIT\",\"error\":\"key issue limit reached for today (5)\"}";

        assertThatThrownBy(() -> client.issueKeys(1, "5h")).isInstanceOfSatisfying(ServiceException.class, e -> {
            assertThat(e.status()).isEqualTo(429);
            assertThat(e.code()).isEqualTo("KEY_ISSUE_LIMIT");
        });
    }

    @Test
    void anUnreachableServiceIsReportedAsUnavailable() {
        BotProperties closed = new BotProperties("t", "http://127.0.0.1:1", "k", "", "http://unused", 50);
        HttpServiceClient offline = new HttpServiceClient(closed, JsonMapper.builder().build(), HttpClient.newHttpClient());

        assertThatThrownBy(() -> offline.stats()).isInstanceOfSatisfying(ServiceException.class, e -> {
            assertThat(e.status()).isZero();
            assertThat(e.code()).isEqualTo("UNAVAILABLE");
        });
    }

    @Test
    void myDataIsParsed() throws Exception {
        answer = "{\"registered\":true,\"active\":true,\"ttl\":\"1d\",\"expiresAt\":\"2026-11-28T10:00:00Z\","
                + "\"workspaces\":[{\"name\":\"indexer\",\"seq\":2,\"projects\":[\"indexer\"],\"bytes\":1048576}],"
                + "\"usage\":{\"uploads\":1,\"patches\":0,\"writeBytes\":10,\"requests\":4,\"keyIssues\":1},"
                + "\"limits\":{\"uploadsPerDay\":10,\"writeBytesPerDay\":314572800,\"patchIntervalSeconds\":30,"
                + "\"requestsPerDay\":5000,\"keyIssuesPerDay\":5,\"maxWorkspaces\":3,\"maxDbBytes\":20971520}}";

        ServiceClient.Me me = client.me(42);

        assertThat(seenRequest.get()).startsWith("GET /internal/me?tgId=42");
        assertThat(me.active()).isTrue();
        assertThat(me.workspaces()).hasSize(1);
        assertThat(me.workspaces().get(0).projects()).containsExactly("indexer");
        assertThat(me.usage().requests()).isEqualTo(4);
        assertThat(me.limits().requestsPerDay()).isEqualTo(5000);
    }

    @Test
    void statsAreParsed() throws Exception {
        answer = "{\"registered\":2,\"activeUsers\":2,\"activePairs\":{\"5h\":0,\"1d\":2,\"7d\":0}}";

        ServiceClient.Stats stats = client.stats();

        assertThat(stats.registered()).isEqualTo(2);
        assertThat(stats.pairs1d()).isEqualTo(2);
    }

    @Test
    void resetIsParsed() throws Exception {
        answer = "{\"reset\":true,\"deletedFiles\":3}";

        ServiceClient.ResetResult result = client.reset(42);

        assertThat(seenRequest.get()).startsWith("POST /internal/reset ").contains("\"tgId\":42");
        assertThat(result.reset()).isTrue();
        assertThat(result.deletedFiles()).isEqualTo(3);
    }
}
