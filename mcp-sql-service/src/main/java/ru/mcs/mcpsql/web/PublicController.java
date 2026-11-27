package ru.mcs.mcpsql.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/** Open to everyone on purpose: only aggregate numbers, see {@link SiteStatsService}. */
@RestController
@ConditionalOnProperty(name = "mcpsql.multi.enabled", havingValue = "true")
public class PublicController {

    private final SiteStatsService stats;

    public PublicController(SiteStatsService stats) {
        this.stats = stats;
    }

    @GetMapping("/public/stats")
    public Map<String, Object> stats() throws IOException {
        return stats.get(Instant.now());
    }
}
