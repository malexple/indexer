package ru.mcs.mcpsql.site;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class StatsProxyTest {

    private HttpServer server;
    private StatsProxy proxy;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile int status = 200;
    private volatile String answer = "{\"users\":{\"active\":3}}";
    private volatile Instant now = Instant.parse("2026-11-27T10:00:00Z");

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/public/stats", exchange -> {
            hits.incrementAndGet();
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        proxy = new StatsProxy(new SiteProperties("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                HttpClient.newHttpClient(), () -> now);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void anAnswerIsKeptForAFewSeconds() {
        StatsProxy.Result first = proxy.get();
        now = now.plusSeconds(10);
        StatsProxy.Result second = proxy.get();

        assertThat(first.body()).contains("\"active\":3");
        assertThat(first.stale()).isFalse();
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(hits.get()).isEqualTo(1);

        now = now.plusSeconds(10);
        answer = "{\"users\":{\"active\":4}}";
        assertThat(proxy.get().body()).contains("\"active\":4");
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    void whenTheServiceFailsTheLastAnswerIsServedAsStaleForTenMinutes() {
        proxy.get();
        status = 500;

        now = now.plus(Duration.ofMinutes(1));
        StatsProxy.Result stale = proxy.get();
        assertThat(stale).isNotNull();
        assertThat(stale.stale()).isTrue();
        assertThat(stale.body()).contains("\"active\":3");

        now = now.plus(Duration.ofMinutes(10));
        assertThat(proxy.get()).isNull();
    }

    @Test
    void withoutAnyGoodAnswerThereIsNothingToServe() {
        status = 500;

        assertThat(proxy.get()).isNull();
    }

    @Test
    void theControllerAnswers503WhenThereIsNothing() {
        status = 500;

        ResponseEntity<String> response = new StatsController(proxy).stats();

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).contains("unavailable");
    }

    @Test
    void theControllerMarksStaleAnswers() {
        proxy.get();
        status = 500;
        now = now.plus(Duration.ofMinutes(1));

        ResponseEntity<String> response = new StatsController(proxy).stats();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("X-Stale")).isEqualTo("true");
    }
}
