package ru.mcs.indexer.files;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public record GitInfo(String commit, String commitShort, String branch, boolean dirty) {

    public static GitInfo unknown() {
        return new GitInfo("unknown", "unknown", "unknown", false);
    }

    public static GitInfo collect(GitRunner git, Path root, Path out) {
        Optional<String> commit = git.text(root, "rev-parse", "HEAD");
        if (commit.isEmpty() || commit.get().isEmpty()) {
            return unknown();
        }
        String full = commit.get();
        String shortHash = git.text(root, "rev-parse", "--short", "HEAD")
                .filter(s -> !s.isEmpty())
                .orElse(full.substring(0, Math.min(7, full.length())));
        String branch = git.text(root, "rev-parse", "--abbrev-ref", "HEAD")
                .filter(s -> !s.isEmpty())
                .orElse("unknown");

        List<String> args = new ArrayList<>(List.of("status", "--porcelain", "--", ".", ":(exclude).index"));
        Path rootNorm = root.toAbsolutePath().normalize();
        Path outNorm = out.toAbsolutePath().normalize();
        if (outNorm.startsWith(rootNorm) && !outNorm.startsWith(rootNorm.resolve(".index"))) {
            StringBuilder rel = new StringBuilder();
            for (Path part : rootNorm.relativize(outNorm)) {
                if (rel.length() > 0) {
                    rel.append('/');
                }
                rel.append(part);
            }
            args.add(":(exclude)" + rel);
        }
        boolean dirty = git.text(root, args.toArray(new String[0]))
                .map(s -> !s.isEmpty())
                .orElse(false);
        return new GitInfo(full, shortHash, branch, dirty);
    }
}