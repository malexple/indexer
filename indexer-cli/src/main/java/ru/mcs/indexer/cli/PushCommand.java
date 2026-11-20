package ru.mcs.indexer.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import ru.mcs.indexer.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

@Command(name = "push", mixinStandardHelpOptions = true, sortOptions = false,
        description = "Uploads a database (--db) or the patches that continue the server's seq (--patch-dir) "
                + "to an mcp-sql-service workspace.")
public final class PushCommand implements Callable<Integer> {

    private static final Pattern PATCH_NAME = Pattern.compile("patch-(\\d+)-(\\d+)\\.sql(?:\\.gz)?");
    private static final Pattern SEQ = Pattern.compile("\"seq\"\\s*:\\s*(-?\\d+)");

    @Option(names = "--server", required = true, paramLabel = "URL",
            description = "Service base URL, e.g. https://mcp.example.org")
    private String server;

    @Option(names = "--workspace", required = true, paramLabel = "NAME", description = "Workspace name on the server.")
    private String workspace;

    @Option(names = "--key", paramLabel = "KEY", defaultValue = "${env:MCPSQL_WRITE_KEY:-}",
            description = "Write key (default: environment variable MCPSQL_WRITE_KEY).")
    private String key;

    @Option(names = "--db", paramLabel = "FILE", description = "Upload this database, replacing the workspace.")
    private Path db;

    @Option(names = "--patch-dir", paramLabel = "DIR",
            description = "Upload, in order, the patch-<from>-<to> files that continue the server's seq.")
    private Path patchDir;

    @Override
    public Integer call() {
        Log log = new Log(false);
        if ((db == null) == (patchDir == null)) {
            log.error("pass exactly one of --db or --patch-dir");
            return 1;
        }
        if (key == null || key.isBlank()) {
            log.error("no key: pass --key or set the MCPSQL_WRITE_KEY environment variable");
            return 1;
        }
        try {
            String base = server.endsWith("/") ? server.substring(0, server.length() - 1) : server;
            String api = base + "/api/workspaces/" + workspace;
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
            return db != null ? pushDatabase(client, api, log) : pushPatches(client, api, log);
        } catch (IllegalArgumentException e) {
            log.error("bad argument: " + e.getMessage());
            return 1;
        } catch (IOException e) {
            log.error("push failed: " + e.getMessage());
            return 2;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("push interrupted");
            return 2;
        }
    }

    private int pushDatabase(HttpClient client, String api, Log log) throws IOException, InterruptedException {
        Path gz = Files.createTempFile("indexer-push-", ".db.gz");
        try {
            try (InputStream in = Files.newInputStream(db);
                 OutputStream out = new GZIPOutputStream(Files.newOutputStream(gz))) {
                in.transferTo(out);
            }
            HttpResponse<String> response = send(client,
                    request(api + "/db").PUT(HttpRequest.BodyPublishers.ofFile(gz)).build());
            return report(response, "database uploaded", log);
        } finally {
            Files.deleteIfExists(gz);
        }
    }

    private int pushPatches(HttpClient client, String api, Log log) throws IOException, InterruptedException {
        HttpResponse<String> info = send(client, request(api).GET().build());
        if (info.statusCode() == 404) {
            log.error("workspace '" + workspace + "' does not exist on the server; upload the database first (--db)");
            return 1;
        }
        if (info.statusCode() / 100 != 2) {
            return report(info, "", log);
        }
        Matcher seqMatch = SEQ.matcher(info.body());
        if (!seqMatch.find()) {
            log.error("unexpected answer from the server: " + info.body());
            return 1;
        }
        long seq = Long.parseLong(seqMatch.group(1));

        Map<Long, Map.Entry<Long, Path>> byFrom = new HashMap<>();
        try (Stream<Path> files = Files.list(patchDir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Matcher m = PATCH_NAME.matcher(file.getFileName().toString());
                if (m.matches()) {
                    byFrom.put(Long.parseLong(m.group(1)), Map.entry(Long.parseLong(m.group(2)), file));
                }
            }
        }
        int sent = 0;
        while (byFrom.containsKey(seq)) {
            Map.Entry<Long, Path> next = byFrom.get(seq);
            HttpResponse<String> response = send(client,
                    request(api + "/patch").POST(HttpRequest.BodyPublishers.ofFile(next.getValue())).build());
            if (response.statusCode() / 100 != 2) {
                return report(response, "", log);
            }
            log.info("applied " + next.getValue().getFileName() + ": " + response.body());
            seq = next.getKey();
            sent++;
        }
        log.info(sent == 0
                ? "server is at seq " + seq + "; no patch in " + patchDir + " continues it"
                : sent + " patch(es) applied, server is now at seq " + seq);
        return 0;
    }

    private HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/octet-stream");
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest request)
            throws IOException, InterruptedException {
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static int report(HttpResponse<String> response, String okLabel, Log log) {
        if (response.statusCode() / 100 == 2) {
            log.info(okLabel + ": " + response.body());
            return 0;
        }
        log.error("server answered " + response.statusCode() + ": " + response.body());
        return 1;
    }
}