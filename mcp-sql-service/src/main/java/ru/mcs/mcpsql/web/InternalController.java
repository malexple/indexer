package ru.mcs.mcpsql.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ru.mcs.mcpsql.users.IssuedKeys;
import ru.mcs.mcpsql.users.KeyService;
import ru.mcs.mcpsql.users.PublicStats;
import ru.mcs.mcpsql.users.QuotaException;
import ru.mcs.mcpsql.users.TtlKind;
import ru.mcs.mcpsql.workspace.WorkspaceService;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** For the Telegram bot only: needs the X-Internal-Key header and is never routed from the internet. */
@RestController
@ConditionalOnProperty(name = "mcpsql.multi.enabled", havingValue = "true")
public class InternalController {

    public record IssueRequest(long tgId, String ttl) {
    }

    public record ResetRequest(long tgId) {
    }

    private final KeyService keys;
    private final WorkspaceService workspaces;

    public InternalController(KeyService keys, WorkspaceService workspaces) {
        this.keys = keys;
        this.workspaces = workspaces;
    }

    @PostMapping("/internal/keys")
    public ResponseEntity<Map<String, Object>> issue(@RequestBody IssueRequest request) {
        try {
            IssuedKeys issued = keys.issue(request.tgId(), TtlKind.parse(request.ttl()), Instant.now());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("readKey", issued.readKey());
            body.put("writeKey", issued.writeKey());
            body.put("statsToken", issued.statsToken());
            body.put("ttl", issued.ttl().label());
            body.put("expiresAt", issued.expiresAt().toString());
            return ResponseEntity.ok(body);
        } catch (QuotaException e) {
            HttpStatus status = switch (e.kind()) {
                case KEY_ISSUE_LIMIT -> HttpStatus.TOO_MANY_REQUESTS;
                case CAPACITY -> HttpStatus.SERVICE_UNAVAILABLE;
                case BANNED -> HttpStatus.FORBIDDEN;
                default -> HttpStatus.TOO_MANY_REQUESTS;
            };
            return ResponseEntity.status(status).body(Map.of("code", e.kind().name(), "error", e.getMessage()));
        }
    }

    @PostMapping("/internal/reset")
    public Map<String, Object> reset(@RequestBody ResetRequest request) throws IOException {
        Optional<Long> userId = keys.reset(request.tgId(), Instant.now());
        if (userId.isEmpty()) {
            return Map.of("reset", false);
        }
        int deleted = workspaces.deleteAllOf(userId.get());
        return Map.of("reset", true, "deletedFiles", deleted);
    }

    @GetMapping("/internal/stats")
    public PublicStats stats() {
        return keys.stats(Instant.now());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
