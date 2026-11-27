package ru.mcs.mcpsql.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
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
import ru.mcs.mcpsql.users.QuotaException;
import ru.mcs.mcpsql.users.UsageService;
import ru.mcs.mcpsql.users.UsageToday;
import ru.mcs.mcpsql.workspace.InsufficientStorageException;
import ru.mcs.mcpsql.workspace.WorkspaceInfo;
import ru.mcs.mcpsql.workspace.WorkspaceService;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

@RestController
public class WorkspaceController {

    private final WorkspaceService workspaces;
    private final UsageService usage;

    public WorkspaceController(WorkspaceService workspaces) {
        this(workspaces, Optional.empty());
    }

    @Autowired
    public WorkspaceController(WorkspaceService workspaces, Optional<UsageService> usage) {
        this.workspaces = workspaces;
        this.usage = usage.orElse(null);
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

    @GetMapping("/api/usage")
    public Map<String, Object> usage(HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("multiUser", usage != null);
        if (usage != null) {
            UsageToday today = usage.today(owner(request), Instant.now());
            body.put("today", today);
            body.put("limits", usage.limits());
        }
        return body;
    }

    @PutMapping("/api/workspaces/{workspace}/db")
    public WorkspaceInfo putDatabase(@PathVariable String workspace, HttpServletRequest request)
            throws IOException, SQLException {
        String key = key(request, workspace);
        if (usage == null) {
            return workspaces.putDatabase(key, request.getInputStream());
        }
        long owner = owner(request);
        usage.checkUpload(owner, Instant.now());
        Counting in = new Counting(request.getInputStream());
        try {
            return workspaces.putDatabase(key, in);
        } finally {
            usage.recordUpload(owner, in.count(), Instant.now());
        }
    }

    @PostMapping("/api/workspaces/{workspace}/patch")
    public Map<String, Object> applyPatch(@PathVariable String workspace, HttpServletRequest request)
            throws IOException, SQLException {
        String key = key(request, workspace);
        PatchApplier.Result result;
        if (usage == null) {
            result = workspaces.applyPatch(key, request.getInputStream());
        } else {
            long owner = owner(request);
            usage.checkPatch(owner, Instant.now());
            Counting in = new Counting(request.getInputStream());
            try {
                result = workspaces.applyPatch(key, in);
            } finally {
                usage.recordPatch(owner, in.count(), Instant.now());
            }
        }
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

    private static final class Counting extends FilterInputStream {

        private long count;

        Counting(InputStream in) {
            super(in);
        }

        long count() {
            return count;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            int n = super.read(buffer, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Map<String, String>> unauthorized(SecurityException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(QuotaException.class)
    ResponseEntity<Map<String, String>> quota(QuotaException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("code", e.kind().name(), "error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(InsufficientStorageException.class)
    ResponseEntity<Map<String, String>> storage(InsufficientStorageException e) {
        return ResponseEntity.status(HttpStatus.INSUFFICIENT_STORAGE).body(Map.of("error", String.valueOf(e.getMessage())));
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
