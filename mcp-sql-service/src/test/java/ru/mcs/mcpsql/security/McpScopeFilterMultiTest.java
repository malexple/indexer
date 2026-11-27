package ru.mcs.mcpsql.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import ru.mcs.mcpsql.config.MultiUserProperties;
import ru.mcs.mcpsql.config.ServiceProperties;
import ru.mcs.mcpsql.users.Principal;
import ru.mcs.mcpsql.users.Scope;
import ru.mcs.mcpsql.workspace.WorkspaceService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class McpScopeFilterMultiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path tmp;

    private McpScopeFilter filter;

    private record Outcome(MockHttpServletResponse response, HttpServletRequest seen, String seenBody) {
    }

    @BeforeEach
    void setUp() throws IOException {
        ServiceProperties properties = new ServiceProperties(tmp.resolve("data"), "r", "w", 8000, 3, 200, 50);
        WorkspaceService workspaces = new WorkspaceService(properties, MultiUserProperties.of(true, 50, 5));
        Path file = workspaces.dbPath("7/ws");
        Files.createDirectories(file.getParent());
        Files.createFile(file);
        filter = new McpScopeFilter(workspaces);
    }

    private static String call(String tool, String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                + "\",\"arguments\":" + arguments + "}}";
    }

    private Outcome post(String uri, String body, Principal principal) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        if (principal != null) {
            request.setAttribute(ApiKeyFilter.PRINCIPAL_ATTR, principal);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
        AtomicReference<String> seenBody = new AtomicReference<>();
        FilterChain chain = (rq, rs) -> {
            seen.set((HttpServletRequest) rq);
            seenBody.set(new String(rq.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        };
        filter.doFilter(request, response, chain);
        return new Outcome(response, seen.get(), seenBody.get());
    }

    private static String text(JsonNode node) {
        String s = node.toString();
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    private static String workspaceArgument(Outcome o) {
        return text(JSON.readTree(o.seenBody()).path("params").path("arguments").path("workspace"));
    }

    private static final Principal SEVEN = new Principal(7, Scope.READ, 1);
    private static final Principal EIGHT = new Principal(8, Scope.READ, 2);

    @Test
    void thePlainEndpointPutsTheOwnerInFrontOfTheClientsName() throws Exception {
        assertThat(workspaceArgument(post("/mcp", call("index_info", "{\"workspace\":\"ws\"}"), SEVEN)))
                .isEqualTo("7/ws");
        assertThat(workspaceArgument(post("/mcp", call("index_info", "{}"), SEVEN))).isEqualTo("7/");
        assertThat(workspaceArgument(post("/mcp", call("index_info", "{\"workspace\":\"\"}"), SEVEN))).isEqualTo("7/");
    }

    @Test
    void aClientCannotNameAnotherOwner() throws Exception {
        String sent = workspaceArgument(post("/mcp", call("index_info", "{\"workspace\":\"8/ws\"}"), SEVEN));

        assertThat(sent).isEqualTo("7/8/ws");
    }

    @Test
    void aBoundPathUsesTheOwnersWorkspaceOnly() throws Exception {
        Outcome own = post("/mcp/ws/demo", call("find_type", "{\"name\":\"A\"}"), SEVEN);
        JsonNode arguments = JSON.readTree(own.seenBody()).path("params").path("arguments");
        assertThat(own.seen().getRequestURI()).isEqualTo("/mcp");
        assertThat(text(arguments.path("workspace"))).isEqualTo("7/ws");
        assertThat(text(arguments.path("project"))).isEqualTo("demo");

        Outcome foreign = post("/mcp/ws/demo", call("find_type", "{\"name\":\"A\"}"), EIGHT);
        assertThat(foreign.seen()).isNull();
        assertThat(foreign.response().getStatus()).isEqualTo(404);
    }

    @Test
    void aConflictingNameOnABoundPathIsRefused() throws Exception {
        Outcome o = post("/mcp/ws", call("index_info", "{\"workspace\":\"other\"}"), SEVEN);

        assertThat(o.seen()).isNull();
        assertThat(o.response().getContentAsString()).contains("bound to workspace 'ws'");
    }

    @Test
    void withoutAPrincipalNothingGetsThrough() throws Exception {
        Outcome o = post("/mcp", call("index_info", "{}"), null);

        assertThat(o.seen()).isNull();
        assertThat(o.response().getStatus()).isEqualTo(401);
    }
}
