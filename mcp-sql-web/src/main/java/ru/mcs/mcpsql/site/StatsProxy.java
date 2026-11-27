package ru.mcs.mcpsql.site;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Fetches /public/stats from the service and keeps the answer for a few seconds, so the number of visitors
 * does not matter to the service. If the service is down, the last answer is served as "stale" for ten minutes.
 */
@Component
public class StatsProxy {

    public record Result(String body, boolean stale) {
    }

    private record Cached(String body, Instant at) {
    }

    static final Duration FRESH = Duration.ofSeconds(15);
    static final Duration STALE_MAX = Duration.ofMinutes(10);

    private static final Logger LOG = LoggerFactory.getLogger(StatsProxy.class);

    private final HttpClient http;
    private final URI uri;
    private final Supplier<Instant> clock;
    private Cached cached;

    @Autowired
    public StatsProxy(SiteProperties properties) {
        this(properties, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(), Instant::now);
    }

    StatsProxy(SiteProperties properties, HttpClient http, Supplier<Instant> clock) {
        String base = properties.serviceUrl();
        this.uri = URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/public/stats");
        this.http = http;
        this.clock = clock;
    }

    /** The statistics as JSON text, or null when there has never been a good answer in the last ten minutes. */
    public synchronized Result get() {
        Instant now = clock.get();
        if (cached != null && now.isBefore(cached.at().plus(FRESH))) {
            return new Result(cached.body(), false);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
                throw new IOException("HTTP " + response.statusCode());
            }
            cached = new Cached(response.body(), now);
            return new Result(cached.body(), false);
        } catch (IOException | RuntimeException e) {
            LOG.warn("statistics are not available: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (cached != null && now.isBefore(cached.at().plus(STALE_MAX))) {
            return new Result(cached.body(), true);
        }
        return null;
    }
}
