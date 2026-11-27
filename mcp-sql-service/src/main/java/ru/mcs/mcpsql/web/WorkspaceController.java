package ru.mcs.mcpsql.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.mcs.indexer.patch.PatchApplier;
import ru.mcs.mcpsql.security.ApiKeyFilter;
import ru.mcs.mcpsql.users.Principal;
import ru.mcs.mcpsql.workspace.WorkspaceInfo;
import ru.mcs.mcpsql.workspace.WorkspaceService;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
public class WorkspaceController {

    private final WorkspaceService workspaces;

    public WorkspaceController(WorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/api/workspaces")
    public List<WorkspaceInfo> list(HttpServletRequest request) throws IOException, SQLException {
        List<WorkspaceInfo> result = new ArrayList<>();
        if (workspaces.multi()) {
            long owner = owner(request);
            for (String name : workspaces.list(owner)) {
                result.add(workspaces.info(owner + "/" + name));
            }
        } else {
            for (String name : workspaces.list()) {
                result.add(workspaces.info(name));
            }
        }
        return result;
    }

    @GetMapping("/api/workspaces/{workspace}")
    public WorkspaceInfo info(@PathVariable String workspace, HttpServletRequest request) throws SQLException {
        return workspaces.info(key(request, workspace));
    }

    @PutMapping("/api/workspaces/{workspace}/db")
    public WorkspaceInfo putDatabase(@PathVariable String workspace, HttpServletRequest request)
            throws IOException, SQLException {
        return workspaces.putDatabase(key(request, workspace), request.getInputStream());
    }

    @PostMapping("/api/workspaces/{workspace}/patch")
    public Map<String, Object> applyPatch(@PathVariable String workspace, HttpServletRequest request)
            throws IOException, SQLException {
        PatchApplier.Result result = workspaces.applyPatch(key(request, workspace), request.getInputStream());
        return Map.of("workspace", workspace, "fromSeq", result.fromSeq(), "toSeq", result.toSeq());
    }

    @DeleteMapping("/api/workspaces/{workspace}")
    public Map<String, Object> delete(@PathVariable String workspace, HttpServletRequest request) throws IOException {
        workspaces.delete(key(request, workspace));
        return Map.of("workspace", workspace, "deleted", true);
    }

    /** The server-side address of a workspace: the owner comes from the key, never from the client. */
    private String key(HttpServletRequest request, String workspace) {
        return workspaces.multi() ? owner(request) + "/" + workspace : workspace;
    }

    private static long owner(HttpServletRequest request) {
        Principal principal = ApiKeyFilter.principal(request);
        if (principal == null) {
            throw new SecurityException("not authenticated");
        }
        return principal.userId();
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Map<String, String>> unauthorized(SecurityException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler({IOException.class, SQLException.class})
    ResponseEntity<Map<String, String>> failure(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
