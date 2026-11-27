package ru.mcs.mcpsql.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.mcs.indexer.db.ProjectNames;
import ru.mcs.mcpsql.users.Principal;
import ru.mcs.mcpsql.workspace.WorkspaceService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Binds an MCP request to a workspace (and optionally a project) taken from the URL:
 * /mcp/{workspace} and /mcp/{workspace}/{project}. The path values are written into the arguments of every
 * tools/call, so a client cannot address anything else on this URL. The request is then handed on as plain /mcp.
 * In multi-user mode every tools/call, also on plain /mcp, gets the owner from the key in front of the workspace
 * ("7/name"); whatever the client wrote is only ever taken as a name inside its own space.
 * The arguments are rewritten in the body (not kept in a ThreadLocal) because tool methods run on another thread.
 */
@Component
@Order(20)
public class McpScopeFilter extends OncePerRequestFilter {

    private static final Pattern PATH = Pattern.compile("^/mcp(?:/([^/]+)(?:/([^/]+))?)?/?$");
    private static final Set<String> PROJECT_TOOLS =
            Set.of("find_type", "context", "read_source", "read_lines", "search", "callers");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_BODY_BYTES = 1 << 20;

    private final WorkspaceService workspaces;

    public McpScopeFilter(WorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Matcher m = PATH.matcher(request.getRequestURI());
        if (!m.matches()) {
            chain.doFilter(request, response);
            return;
        }
        String workspace = m.group(1);
        String project = m.group(2);
        boolean multi = workspaces.multi();
        if (workspace == null && !multi) {
            chain.doFilter(request, response);
            return;
        }
        Long owner = null;
        if (multi) {
            Principal principal = ApiKeyFilter.principal(request);
            if (principal == null) {
                plain(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized");
                return;
            }
            owner = principal.userId();
        }
        if (workspace != null && (!exists(owner, workspace) || (project != null && !ProjectNames.isValid(project)))) {
            plain(response, HttpServletResponse.SC_NOT_FOUND, "not found");
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            plain(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "request is too large");
            return;
        }
        byte[] out = body;
        if (body.length > 0) {
            Rewrite rewrite = rewrite(body, owner, workspace, project);
            if (rewrite.error() != null) {
                jsonRpcError(response, rewrite.id(), rewrite.error());
                return;
            }
            out = rewrite.body();
        }
        chain.doFilter(new Rewritten(request, out), response);
    }

    private boolean exists(Long owner, String workspace) {
        try {
            String key = owner == null ? workspace : owner + "/" + workspace;
            return Files.isRegularFile(workspaces.dbPath(key));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private record Rewrite(byte[] body, String error, JsonNode id) {
    }

    private static Rewrite rewrite(byte[] body, Long owner, String workspace, String project) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (RuntimeException e) {
            return new Rewrite(body, null, null);
        }
        if (root.isArray()) {
            for (JsonNode element : root) {
                String error = apply(element, owner, workspace, project);
                if (error != null) {
                    return new Rewrite(null, error, element.path("id"));
                }
            }
        } else {
            String error = apply(root, owner, workspace, project);
            if (error != null) {
                return new Rewrite(null, error, root.path("id"));
            }
        }
        return new Rewrite(JSON.writeValueAsBytes(root), null, null);
    }

    private static String apply(JsonNode message, Long owner, String workspace, String project) {
        if (!message.isObject() || !"\"tools/call\"".equals(message.path("method").toString())) {
            return null;
        }
        JsonNode params = message.path("params");
        if (!params.isObject()) {
            return null;
        }
        String tool = text(params.path("name"));
        JsonNode existing = params.path("arguments");
        ObjectNode arguments;
        if (existing.isObject()) {
            arguments = (ObjectNode) existing;
        } else if (existing.isMissingNode() || existing.isNull()) {
            arguments = ((ObjectNode) params).putObject("arguments");
        } else {
            return null;
        }
        String error = bindWorkspace(arguments, owner, workspace);
        if (error == null && project != null && PROJECT_TOOLS.contains(tool)) {
            error = bind(arguments, "project", project);
        }
        return error;
    }

    /** Without an owner (local mode) the path value is written as is; with one it gets the "owner/" prefix. */
    private static String bindWorkspace(ObjectNode arguments, Long owner, String bound) {
        JsonNode current = arguments.path("workspace");
        String name = isBlank(current) ? "" : text(current);
        if (bound != null) {
            if (!name.isEmpty() && !bound.equals(name)) {
                return "this endpoint is bound to workspace '" + bound
                        + "'; omit the 'workspace' argument or pass the same value";
            }
            name = bound;
        }
        arguments.put("workspace", owner == null ? name : owner + "/" + name);
        return null;
    }

    private static String bind(ObjectNode arguments, String name, String bound) {
        JsonNode current = arguments.path(name);
        if (!isBlank(current) && !bound.equals(text(current))) {
            return "this endpoint is bound to " + name + " '" + bound + "'; omit the '" + name
                    + "' argument or pass the same value";
        }
        arguments.put(name, bound);
        return null;
    }

    private static boolean isBlank(JsonNode node) {
        return node.isMissingNode() || node.isNull() || text(node).isBlank();
    }

    /** The text of a JSON string node without quotes; other nodes give their JSON form. */
    private static String text(JsonNode node) {
        String s = node.toString();
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    private static void plain(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(message);
    }

    private static void jsonRpcError(HttpServletResponse response, JsonNode id, String message) throws IOException {
        ObjectNode reply = JSON.createObjectNode();
        reply.put("jsonrpc", "2.0");
        if (id == null || id.isMissingNode()) {
            reply.putNull("id");
        } else {
            reply.set("id", id);
        }
        ObjectNode details = reply.putObject("error");
        details.put("code", -32602);
        details.put("message", message);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getOutputStream().write(JSON.writeValueAsBytes(reply));
    }

    /** The same request seen as plain /mcp with the already read (and rewritten) body. */
    private static final class Rewritten extends HttpServletRequestWrapper {

        private final byte[] body;

        Rewritten(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public String getRequestURI() {
            return getContextPath() + "/mcp";
        }

        @Override
        public StringBuffer getRequestURL() {
            String full = super.getRequestURL().toString();
            String original = super.getRequestURI();
            int cut = full.endsWith(original) ? full.length() - original.length() : full.length();
            return new StringBuffer(full.substring(0, cut) + getRequestURI());
        }

        @Override
        public String getServletPath() {
            return "/mcp";
        }

        @Override
        public String getPathInfo() {
            return null;
        }

        @Override
        public String getPathTranslated() {
            return null;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new IllegalStateException("async reads are not supported");
                }

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public String getHeader(String name) {
            return "content-length".equalsIgnoreCase(name) ? String.valueOf(body.length) : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return "content-length".equalsIgnoreCase(name)
                    ? Collections.enumeration(List.of(String.valueOf(body.length)))
                    : super.getHeaders(name);
        }
    }
}
