package ru.mcs.indexer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class PushCommandTest {

    @TempDir
    Path tmp;

    private HttpServer server;
    private final List<String> received = new ArrayList<>();
    private final List<String> auth = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/workspaces/ws", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        byte[] body = exchange.getRequestBody().readAllBytes();
        auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
        if (path.endsWith("/patch")) {
            received.add("patch:" + new String(body, StandardCharsets.UTF_8));
        } else if (path.endsWith("/db")) {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
                received.add("db:" + new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        byte[] answer = "{\"workspace\":\"ws\",\"seq\":2}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, answer.length);
        exchange.getResponseBody().write(answer);
        exchange.close();
    }

    private String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    @Test
    void sendsOnlyThePatchesThatContinueTheServerSeqInOrder() throws Exception {
        Path dir = Files.createDirectory(tmp.resolve("patches"));
        Files.writeString(dir.resolve("patch-1-2.sql.gz"), "old");
        Files.writeString(dir.resolve("patch-2-3.sql.gz"), "a");
        Files.writeString(dir.resolve("patch-3-4.sql.gz"), "b");
        Files.writeString(dir.resolve("patch-9-10.sql.gz"), "gap");

        int code = IndexerMain.run(new String[]{"push", "--server", url(), "--workspace", "ws", "--key", "k",
                "--patch-dir", dir.toString()});

        assertThat(code).isZero();
        assertThat(received).containsExactly("patch:a", "patch:b");
        assertThat(auth).allMatch("Bearer k"::equals);
    }

    @Test
    void uploadsTheDatabaseCompressed() throws Exception {
        Path db = tmp.resolve("index.db");
        Files.writeString(db, "database bytes");

        int code = IndexerMain.run(new String[]{"push", "--server", url(), "--workspace", "ws", "--key", "k",
                "--db", db.toString()});

        assertThat(code).isZero();
        assertThat(received).containsExactly("db:database bytes");
    }

    @Test
    void refusesWithoutAKeyOrWithBothModes() {
        assertThat(IndexerMain.run(new String[]{"push", "--server", url(), "--workspace", "ws", "--key", "",
                "--db", tmp.toString()})).isEqualTo(1);
        assertThat(IndexerMain.run(new String[]{"push", "--server", url(), "--workspace", "ws", "--key", "k"}))
                .isEqualTo(1);
    }
}