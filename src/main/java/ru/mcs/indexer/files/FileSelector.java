package ru.mcs.indexer.files;

import ru.mcs.indexer.IndexerOptions;
import ru.mcs.indexer.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.nio.file.FileVisitOption;

public final class FileSelector {

    private static final Set<String> EXCLUDED_ANYWHERE =
            Set.of(".git", ".gradle", ".idea", ".vscode", "node_modules", ".index");
    private static final Set<String> EXCLUDED_BEFORE_SRC = Set.of("build", "out", "target");
    private static final int BINARY_PROBE_BYTES = 8192;

    private final IndexerOptions options;
    private final Log log;
    private final GitRunner git;
    private final Path root;
    private final GitIgnoreMatcher extraExcludes = new GitIgnoreMatcher();
    private final List<SkippedFile> skipped = new ArrayList<>();

    public FileSelector(IndexerOptions options, Log log, GitRunner git) {
        this.options = options;
        this.log = log;
        this.git = git;
        this.root = options.root();
        this.extraExcludes.addRules("", options.excludes());
    }

    public SelectionResult select() throws IOException {
        skipped.clear();

        Optional<List<String>> fromGit = listViaGit();
        boolean viaGit = fromGit.isPresent();
        List<String> candidates = viaGit ? fromGit.get() : walk();
        log.debug("candidates: " + candidates.size() + (viaGit ? " (git)" : " (walk)"));

        TreeSet<String> unique = new TreeSet<>(candidates);
        List<SelectedFile> selected = new ArrayList<>();
        for (String rel : unique) {
            SelectedFile file = accept(rel);
            if (file != null) {
                selected.add(file);
            }
        }
        skipped.sort(Comparator.comparing(SkippedFile::path).thenComparing(SkippedFile::stage));
        return new SelectionResult(List.copyOf(selected), List.copyOf(skipped), viaGit);
    }

    private Optional<List<String>> listViaGit() {
        Optional<String> inside = git.text(root, "rev-parse", "--is-inside-work-tree");
        if (inside.isEmpty() || !"true".equals(inside.get())) {
            return Optional.empty();
        }
        Optional<GitRunner.Result> listing = git.run(root,
                "ls-files", "-z", "--cached", "--others", "--exclude-standard");
        if (listing.isEmpty() || listing.get().exitCode() != 0) {
            return Optional.empty();
        }
        byte[] bytes = listing.get().stdout();
        List<String> paths = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == 0) {
                if (i > start) {
                    paths.add(new String(bytes, start, i - start, StandardCharsets.UTF_8));
                }
                start = i + 1;
            }
        }
        if (start < bytes.length) {
            paths.add(new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8));
        }
        return Optional.of(paths);
    }

    private List<String> walk() throws IOException {
        GitIgnoreMatcher ignore = new GitIgnoreMatcher();
        loadIgnoreFile(ignore, "", root.resolve(".git").resolve("info").resolve("exclude"));
        List<String> result = new ArrayList<>();

        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        String rel = dir.equals(root) ? "" : relative(dir);
                        if (!rel.isEmpty()) {
                            if (hasExcludedSegment(rel.split("/"), rel.split("/").length)
                                    || ignore.isIgnored(rel, true)
                                    || extraExcludes.isIgnored(rel, true)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            if (Files.exists(dir.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                                log.debug("nested repository skipped: " + rel);
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                        }
                        loadIgnoreFile(ignore, rel, dir.resolve(".gitignore"));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (!attrs.isRegularFile()) {
                            return FileVisitResult.CONTINUE;
                        }
                        String rel = relative(file);
                        if (!ignore.isIgnored(rel, false)) {
                            result.add(rel);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        skipped.add(new SkippedFile(relative(file), "READ", String.valueOf(exc)));
                        return FileVisitResult.CONTINUE;
                    }
                });
        return result;
    }

    private void loadIgnoreFile(GitIgnoreMatcher matcher, String base, Path file) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            matcher.addRules(base, Arrays.asList(text.split("\n", -1)));
        } catch (IOException e) {
            String name = base.isEmpty() ? file.getFileName().toString() : base + "/" + file.getFileName();
            skipped.add(new SkippedFile(name, "READ", String.valueOf(e)));
        }
    }

    private SelectedFile accept(String rel) {
        String[] segments = rel.split("/");
        if (hasExcludedSegment(segments, segments.length - 1) || isMinified(segments[segments.length - 1])) {
            return null;
        }
        String ext = extensionOf(segments[segments.length - 1]);
        if (ext == null || !options.extensions().contains(ext)) {
            return null;
        }
        if (extraExcludes.isIgnored(rel, false)) {
            return null;
        }

        Path absolute;
        BasicFileAttributes attrs;
        try {
            absolute = root.resolve(rel);
            attrs = Files.readAttributes(absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException | InvalidPathException e) {
            skipped.add(new SkippedFile(rel, "READ", String.valueOf(e)));
            return null;
        }
        if (!attrs.isRegularFile()) {
            return null;
        }

        long limitBytes = options.maxFileKb() * 1024L;
        if (attrs.size() > limitBytes) {
            skipped.add(new SkippedFile(rel, "SKIP_SIZE",
                    "size " + attrs.size() + " bytes exceeds limit " + options.maxFileKb() + " KB"));
            return null;
        }

        try (InputStream in = Files.newInputStream(absolute)) {
            byte[] head = in.readNBytes(BINARY_PROBE_BYTES);
            for (byte b : head) {
                if (b == 0) {
                    skipped.add(new SkippedFile(rel, "SKIP_BINARY", "NUL byte in first "
                            + BINARY_PROBE_BYTES + " bytes"));
                    return null;
                }
            }
        } catch (IOException e) {
            skipped.add(new SkippedFile(rel, "READ", String.valueOf(e)));
            return null;
        }
        return new SelectedFile(rel, absolute, attrs.size());
    }

    static boolean hasExcludedSegment(String[] segments, int dirCount) {
        boolean seenSrc = false;
        for (int i = 0; i < dirCount; i++) {
            String segment = segments[i];
            if (EXCLUDED_ANYWHERE.contains(segment)) {
                return true;
            }
            if (!seenSrc && EXCLUDED_BEFORE_SRC.contains(segment)) {
                return true;
            }
            if (segment.equals("src")) {
                seenSrc = true;
            }
        }
        return false;
    }

    static boolean isMinified(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".min.js") || lower.endsWith(".min.css");
    }

    static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String relative(Path path) {
        Path rel = root.relativize(path);
        StringBuilder sb = new StringBuilder();
        for (Path part : rel) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(part.toString());
        }
        return sb.toString();
    }
}