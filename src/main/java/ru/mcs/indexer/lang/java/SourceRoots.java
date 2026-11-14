package ru.mcs.indexer.lang.java;

import ru.mcs.indexer.files.SelectedFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Finds Java source roots (.../src/main/java, .../src/test/java) from the selected file paths. */
public final class SourceRoots {

    private SourceRoots() {
    }

    public static List<Path> discover(Path projectRoot, List<SelectedFile> files) {
        TreeSet<String> roots = new TreeSet<>();
        for (SelectedFile file : files) {
            if (!file.path().endsWith(".java")) {
                continue;
            }
            String[] segments = file.path().split("/");
            for (int i = 0; i + 3 < segments.length; i++) {
                if (segments[i].equals("src")
                        && (segments[i + 1].equals("main") || segments[i + 1].equals("test"))
                        && segments[i + 2].equals("java")) {
                    roots.add(String.join("/", java.util.Arrays.copyOfRange(segments, 0, i + 3)));
                    break;
                }
            }
        }
        List<Path> result = new ArrayList<>();
        for (String root : roots) {
            result.add(projectRoot.resolve(root));
        }
        return result;
    }
}