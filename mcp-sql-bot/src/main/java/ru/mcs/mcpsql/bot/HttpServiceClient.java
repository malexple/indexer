package ru.mcs.mcpsql.bot;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Calls /internal/** of mcp-sql-service with the internal key. */
@Component
public class HttpServiceClient implements ServiceClient {

    private final HttpClient http;
    private final JsonMapper json;
    private final String base;
    private final String internalKey;

    public HttpServiceClient(BotProperties properties, JsonMapper json, HttpClient http) {
        this.http = http;
        this.json = json;
        String url = properties.serviceUrl();
        this.base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.internalKey = properties.internalKey();
    }

    @Override
    public IssuedKeys issueKeys(long tgId, String ttl) throws ServiceException {
        ObjectNode body = json.createObjectNode();
        body.put("tgId", tgId);
        body.put("ttl", ttl);
        JsonNode r = send("POST", "/internal/keys", body);
        return new IssuedKeys(str(r.path("readKey")), str(r.path("writeKey")), str(r.path("statsToken")),
                str(r.path("ttl")), str(r.path("expiresAt")));
    }

    @Override
    public ResetResult reset(long tgId) throws ServiceException {
        ObjectNode body = json.createObjectNode();
        body.put("tgId", tgId);
        JsonNode r = send("POST", "/internal/reset", body);
        return new ResetResult(r.path("reset").asBoolean(), r.path("deletedFiles").intValue());
    }

    @Override
    public Me me(long tgId) throws ServiceException {
        JsonNode r = send("GET", "/internal/me?tgId=" + tgId, null);
        List<WorkspaceView> workspaces = new ArrayList<>();
        for (JsonNode w : r.path("workspaces")) {
            List<String> projects = new ArrayList<>();
            for (JsonNode p : w.path("projects")) {
                projects.add(str(p));
            }
            workspaces.add(new WorkspaceView(str(w.path("name")), w.path("seq").longValue(), projects,
                    w.path("bytes").longValue()));
        }
        JsonNode u = r.path("usage");
        JsonNode l = r.path("limits");
        return new Me(r.path("registered").asBoolean(), r.path("active").asBoolean(), str(r.path("ttl")),
                str(r.path("expiresAt")), workspaces,
                new Usage(u.path("uploads").longValue(), u.path("patches").longValue(),
                        u.path("writeBytes").longValue(), u.path("requests").longValue(),
                        u.path("keyIssues").longValue()),
                new Limits(l.path("uploadsPerDay").longValue(), l.path("writeBytesPerDay").longValue(),
                        l.path("patchIntervalSeconds").longValue(), l.path("requestsPerDay").longValue(),
                        l.path("keyIssuesPerDay").longValue(), l.path("maxWorkspaces").longValue(),
                        l.path("maxDbBytes").longValue()));
    }

    @Override
    public Stats stats() throws ServiceException {
        JsonNode r = send("GET", "/internal/stats", null);
        JsonNode pairs = r.path("activePairs");
        return new Stats(r.path("registered").longValue(), r.path("activeUsers").longValue(),
                pairs.path("5h").longValue(), pairs.path("1d").longValue(), pairs.path("7d").longValue());
    }

    private JsonNode send(String method, String path, ObjectNode body) throws ServiceException {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("X-Internal-Key", internalKey);
            if (body == null) {
                builder.GET();
            } else {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode root = response.body() == null || response.body().isBlank()
                    ? json.createObjectNode() : json.readTree(response.body());
            if (response.statusCode() / 100 != 2) {
                throw new ServiceException(response.statusCode(), str(root.path("code")), str(root.path("error")));
            }
            return root;
        } catch (IOException | RuntimeException e) {
            throw new ServiceException(0, "UNAVAILABLE", String.valueOf(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException(0, "UNAVAILABLE", "interrupted");
        }
    }

    private static String str(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? "" : node.asString();
    }
}
