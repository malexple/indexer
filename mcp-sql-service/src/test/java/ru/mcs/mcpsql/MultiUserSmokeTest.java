package ru.mcs.mcpsql;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import ru.mcs.indexer.db.Schema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/** Starts the whole application in multi-user mode and walks through the real HTTP endpoints. */
class MultiUserSmokeTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String INTERNAL_KEY = "smoke-internal";

    @TempDir
    static Path tmp;

    private static ConfigurableApplicationContext context;
    private static HttpClient http;
    private static String base;

    private record Response(int status, String body) {
    }

    @BeforeAll
    static void start() {
        context = new SpringApplicationBuilder(McpSqlServiceApplication.class).run(
                "--server.port=0",
                "--spring.main.banner-mode=off",
                "--mcpsql.data-dir=" + tmp.resolve("data").toString().replace('\\', '/'),
                "--mcpsql.multi.enabled=true",
                "--mcpsql.multi.internal-key=" + INTERNAL_KEY,
                "--mcpsql.multi.min-free-disk-mb=0");
        base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
        http = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stop() {
        if (context != null) {
            context.close();
        }
    }

    private static Response send(String method, String path, String bearer, String internal, String contentType,
                                 byte[] body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        if (internal != null) {
            builder.header("X-Internal-Key", internal);
        }
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        builder.header("Accept", "application/json, text/event-stream");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body));
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private static Response get(String path, String bearer) throws Exception {
        return send("GET", path, bearer, null, null, null);
    }

    private static JsonNode issue(long tgId) throws Exception {
        Response r = send("POST", "/internal/keys", null, INTERNAL_KEY, "application/json",
                ("{\"tgId\":" + tgId + ",\"ttl\":\"1d\"}").getBytes(StandardCharsets.UTF_8));
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    private static String text(JsonNode node) {
        return node.asString();
    }

    private static byte[] indexDb() throws Exception {
        Path file = tmp.resolve("demo-" + System.nanoTime() + ".db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE meta (scope TEXT NOT NULL, key TEXT NOT NULL, value TEXT, PRIMARY KEY (scope, key))");
            s.execute("INSERT INTO meta VALUES ('', 'schema_version', '" + Schema.VERSION + "')");
            s.execute("INSERT INTO meta VALUES ('', 'db_id', 'smoke-db')");
            s.execute("INSERT INTO meta VALUES ('', 'seq', '1')");
            s.execute("CREATE TABLE projects (slug TEXT)");
            s.execute("INSERT INTO projects VALUES ('demo')");
        }
        return Files.readAllBytes(file);
    }

    private static final byte[] TOOLS_LIST =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void everyUserGetsTheirOwnSpaceAndNothingElseLeaks() throws Exception {
        JsonNode alice = issue(1001);
        JsonNode bob = issue(1002);
        String aliceWrite = text(alice.path("writeKey"));
        String aliceRead = text(alice.path("readKey"));
        String bobRead = text(bob.path("readKey"));

        assertThat(get("/api/workspaces", null).status()).isEqualTo(401);
        assertThat(get("/api/workspaces", "read-secret").status()).isEqualTo(401);
        assertThat(get("/api/workspaces", aliceRead).status()).isEqualTo(401);

        Response upload = send("PUT", "/api/workspaces/demo/db", aliceWrite, null, "application/octet-stream", indexDb());
        assertThat(upload.status()).as(upload.body()).isEqualTo(200);
        assertThat(upload.body()).contains("\"workspace\":\"demo\"").contains("smoke-db");

        assertThat(get("/api/workspaces", aliceWrite).body()).contains("\"demo\"");
        assertThat(get("/api/workspaces", text(bob.path("writeKey"))).body()).isEqualTo("[]");

        Response mine = send("POST", "/mcp/demo", aliceRead, null, "application/json", TOOLS_LIST);
        assertThat(mine.status()).as(mine.body()).isEqualTo(200);
        assertThat(mine.body()).contains("index_info");
        assertThat(send("POST", "/mcp/demo", bobRead, null, "application/json", TOOLS_LIST).status()).isEqualTo(404);
        assertThat(send("POST", "/mcp/nope", aliceRead, null, "application/json", TOOLS_LIST).status()).isEqualTo(404);
        assertThat(send("POST", "/mcp/demo", null, null, "application/json", TOOLS_LIST).status()).isEqualTo(401);

        assertThat(send("DELETE", "/api/workspaces/demo", aliceRead, null, null, null).status()).isEqualTo(401);
        assertThat(get("/api/usage", aliceWrite).body()).contains("\"uploads\":1");
    }

    @Test
    void publicAndInternalEndpointsAreSeparated() throws Exception {
        Response stats = get("/public/stats", null);
        assertThat(stats.status()).isEqualTo(200);
        assertThat(stats.body()).contains("\"users\"").contains("\"limits\"").doesNotContain("mcp_");

        assertThat(get("/internal/stats", null).status()).isEqualTo(401);
        assertThat(send("GET", "/internal/stats", null, "wrong", null, null).status()).isEqualTo(401);
        assertThat(send("GET", "/internal/stats", null, INTERNAL_KEY, null, null).status()).isEqualTo(200);
        assertThat(get("/health", null).status()).isEqualTo(200);
    }

    @Test
    void aResetRevokesTheKeysAndDeletesTheDatabases() throws Exception {
        JsonNode carol = issue(1003);
        String write = text(carol.path("writeKey"));
        assertThat(send("PUT", "/api/workspaces/mine/db", write, null, "application/octet-stream", indexDb()).status())
                .isEqualTo(200);

        Response reset = send("POST", "/internal/reset", null, INTERNAL_KEY, "application/json",
                "{\"tgId\":1003}".getBytes(StandardCharsets.UTF_8));

        assertThat(reset.body()).contains("\"reset\":true").contains("\"deletedFiles\":1");
        assertThat(get("/api/workspaces", write).status()).isEqualTo(401);
    }
}
