package ru.mcs.mcpsql.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import ru.mcs.mcpsql.config.ServiceProperties;
import ru.mcs.mcpsql.workspace.WorkspaceService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class McpScopeFilterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path tmp;

    private McpScopeFilter filter;

    private record Outcome(MockHttpServletRequest original, MockHttpServletResponse response,
                           HttpServletRequest seen, String seenBody) {
    }

    @BeforeEach
    void setUp() throws IOException {
        ServiceProperties properties = new ServiceProperties(tmp.resolve("data"), "r", "w", 8000, 3, 200, 50);
        WorkspaceService workspaces = new WorkspaceService(properties);
        Files.createFile(workspaces.dbPath("ws"));
        filter = new McpScopeFilter(workspaces);
    }

    private static String call(String tool, String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                + "\",\"arguments\":" + arguments + "}}";
    }

    private Outcome post(String uri, String body) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
        AtomicReference<String> seenBody = new AtomicReference<>();
        FilterChain chain = (rq, rs) -> {
            seen.set((HttpServletRequest) rq);
            seenBody.set(new String(rq.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        };
        filter.doFilter(request, response, chain);
        return new Outcome(request, response, seen.get(), seenBody.get());
    }

    private static String text(JsonNode node) {
        String s = node.toString();
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    @Test
    void workspaceAndProjectFromThePathAreWrittenIntoTheArguments() throws Exception {
        Outcome o = post("/mcp/ws/demo", call("find_type", "{\"name\":\"A\"}"));

        assertThat(o.seen()).isNotNull();
        assertThat(o.seen().getRequestURI()).isEqualTo("/mcp");
        assertThat(o.seen().getServletPath()).isEqualTo("/mcp");
        JsonNode arguments = JSON.readTree(o.seenBody()).path("params").path("arguments");
        assertThat(text(arguments.path("name"))).isEqualTo("A");
        assertThat(text(arguments.path("workspace"))).isEqualTo("ws");
        assertThat(text(arguments.path("project"))).isEqualTo("demo");
        assertThat(o.seen().getContentLength()).isEqualTo(o.seenBody().getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void workspaceOnlyPathLeavesTheProjectToTheCaller() throws Exception {
        Outcome o = post("/mcp/ws", call("find_type", "{\"name\":\"A\",\"project\":\"other\"}"));

        JsonNode arguments = JSON.readTree(o.seenBody()).path("params").path("arguments");
        assertThat(text(arguments.path("workspace"))).isEqualTo("ws");
        assertThat(text(arguments.path("project"))).isEqualTo("other");
    }

    @Test
    void toolsWithoutAProjectParameterGetOnlyTheWorkspace() throws Exception {
        Outcome o = post("/mcp/ws/demo", call("sql", "{\"query\":\"select 1\"}"));

        JsonNode arguments = JSON.readTree(o.seenBody()).path("params").path("arguments");
        assertThat(text(arguments.path("workspace"))).isEqualTo("ws");
        assertThat(arguments.has("project")).isFalse();
    }

    @Test
    void missingArgumentsObjectIsCreated() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"index_info\"}}";
        Outcome o = post("/mcp/ws", body);

        JsonNode arguments = JSON.readTree(o.seenBody()).path("params").path("arguments");
        assertThat(text(arguments.path("workspace"))).isEqualTo("ws");
    }

    @Test
    void aDifferentWorkspaceOrProjectInTheArgumentsIsRefused() throws Exception {
        Outcome other = post("/mcp/ws/demo", call("index_info", "{\"workspace\":\"other\"}"));
        assertThat(other.seen()).isNull();
        JsonNode reply = JSON.readTree(other.response().getContentAsString());
        assertThat(text(reply.path("error").path("code"))).isEqualTo("-32602");
        assertThat(text(reply.path("id"))).isEqualTo("7");
        assertThat(text(reply.path("error").path("message"))).contains("bound to workspace 'ws'");

        Outcome project = post("/mcp/ws/demo", call("find_type", "{\"name\":\"A\",\"project\":\"other\"}"));
        assertThat(project.seen()).isNull();
        assertThat(project.response().getContentAsString()).contains("bound to project 'demo'");
    }

    @Test
    void theSameValueInTheArgumentsIsAccepted() throws Exception {
        Outcome o = post("/mcp/ws/demo", call("find_type", "{\"name\":\"A\",\"workspace\":\"ws\",\"project\":\"demo\"}"));

        assertThat(o.seen()).isNotNull();
    }

    @Test
    void otherMessagesPassThroughAndOnlyTheAddressChanges() throws Exception {
        String list = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";
        Outcome o = post("/mcp/ws/demo", list);

        assertThat(o.seen().getRequestURI()).isEqualTo("/mcp");
        assertThat(JSON.readTree(o.seenBody())).isEqualTo(JSON.readTree(list));
    }

    @Test
    void unknownOrInvalidWorkspacesAreNotFound() throws Exception {
        Outcome unknown = post("/mcp/nope/demo", call("index_info", "{}"));
        assertThat(unknown.response().getStatus()).isEqualTo(404);
        assertThat(unknown.seen()).isNull();

        Outcome invalid = post("/mcp/a b/demo", call("index_info", "{}"));
        assertThat(invalid.response().getStatus()).isEqualTo(404);
        assertThat(invalid.seen()).isNull();

        Outcome dots = post("/mcp/../demo", call("index_info", "{}"));
        assertThat(dots.response().getStatus()).isEqualTo(404);
        assertThat(dots.seen()).isNull();
    }

    @Test
    void thePlainEndpointIsUntouched() throws Exception {
        Outcome o = post("/mcp", call("index_info", "{}"));

        assertThat(o.seen()).isSameAs(o.original());
    }
}
