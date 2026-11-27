package ru.mcs.mcpsql.site;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class StatsController {

    private final StatsProxy proxy;

    public StatsController(StatsProxy proxy) {
        this.proxy = proxy;
    }

    @GetMapping(value = "/api/stats", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> stats() {
        StatsProxy.Result result = proxy.get();
        if (result == null) {
            return ResponseEntity.status(503).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"unavailable\"}");
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .header("X-Stale", String.valueOf(result.stale()))
                .body(result.body());
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
