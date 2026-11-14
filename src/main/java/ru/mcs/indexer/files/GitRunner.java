package ru.mcs.indexer.files;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class GitRunner {

    public record Result(int exitCode, byte[] stdout) {
        public String text() {
            return new String(stdout, StandardCharsets.UTF_8);
        }
    }

    private final long timeoutSeconds;
    private final boolean enabled;

    public GitRunner() {
        this(30, true);
    }

    private GitRunner(long timeoutSeconds, boolean enabled) {
        this.timeoutSeconds = timeoutSeconds;
        this.enabled = enabled;
    }

    public static GitRunner disabled() {
        return new GitRunner(1, false);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Optional<Result> run(Path dir, String... args) {
        if (!enabled) {
            return Optional.empty();
        }
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");

        final Process process = start(builder);
        if (process == null) {
            return Optional.empty();
        }
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // stdin is not needed
        }

        CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> {
            try (InputStream in = process.getInputStream()) {
                return in.readAllBytes();
            } catch (IOException e) {
                return new byte[0];
            }
        });

        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            byte[] bytes = stdout.get(10, TimeUnit.SECONDS);
            return Optional.of(new Result(process.exitValue(), bytes));
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException | TimeoutException e) {
            process.destroyForcibly();
            return Optional.empty();
        }
    }

    public Optional<String> text(Path dir, String... args) {
        return run(dir, args)
                .filter(result -> result.exitCode() == 0)
                .map(result -> result.text().trim());
    }

    private static Process start(ProcessBuilder builder) {
        try {
            return builder.start();
        } catch (IOException e) {
            return null;
        }
    }
}