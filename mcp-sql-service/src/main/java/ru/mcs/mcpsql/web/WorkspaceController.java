package ru.mcs.mcpsql.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.mcs.indexer.patch.PatchApplier;
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
    public List<WorkspaceInfo> list() throws IOException, SQLException {
        List<WorkspaceInfo> result = new ArrayList<>();
        for (String name : workspaces.list()) {
            result.add(workspaces.info(name));
        }
        return result;
    }

    @GetMapping("/api/workspaces/{workspace}")
    public WorkspaceInfo info(@PathVariable String workspace) throws SQLException {
        return workspaces.info(workspace);
    }

    @PutMapping("/api/workspaces/{workspace}/db")
    public WorkspaceInfo putDatabase(@PathVariable String workspace, HttpServletRequest request)
            throws IOException, SQLException {
        return workspaces.putDatabase(workspace, request.getInputStream());
    }

    @PostMapping("/api/workspaces/{workspace}/patch")
    public Map<String, Object> applyPatch(@PathVariable String workspace, HttpServletRequest request)
            throws IOException, SQLException {
        PatchApplier.Result result = workspaces.applyPatch(workspace, request.getInputStream());
        return Map.of("workspace", workspace, "fromSeq", result.fromSeq(), "toSeq", result.toSeq());
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