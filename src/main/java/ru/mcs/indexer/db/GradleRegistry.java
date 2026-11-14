package ru.mcs.indexer.db;

import ru.mcs.indexer.lang.FileFacts;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Collects Gradle facts of all files, then writes gradle_modules, gradle_deps and files.module in one
 * transaction once every file is in the database. Used only from the single writer thread.
 */
final class GradleRegistry {

    private record Dep(String moduleDir, FileFacts.GradleDepRow row) {
    }

    private final Set<String> includeDirs = new TreeSet<>();
    private final Map<String, String> buildFiles = new TreeMap<>();
    private final List<Dep> deps = new ArrayList<>();
    private boolean hasSettings;
    private String rootName;

    void collect(String path, FileFacts facts) {
        if (facts == null) {
            return;
        }
        String dir = parentDir(path);
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.equals("settings.gradle") || name.equals("settings.gradle.kts")) {
            hasSettings = true;
            if (facts.gradleRootName() != null) {
                rootName = facts.gradleRootName();
            }
            for (String include : facts.gradleIncludes()) {
                String moduleDir = include.startsWith(":") ? include.substring(1) : include;
                moduleDir = moduleDir.replace(':', '/');
                if (!moduleDir.isEmpty()) {
                    includeDirs.add(moduleDir);
                }
            }
        }
        if (name.equals("build.gradle") || name.equals("build.gradle.kts")) {
            buildFiles.putIfAbsent(dir, path);
        }
        for (FileFacts.GradleDepRow dep : facts.gradleDeps()) {
            deps.add(new Dep(dir, dep));
        }
    }

    void finish(Db db, String defaultRootName) throws SQLException {
        Set<String> dirs = new TreeSet<>();
        boolean hasRoot;
        if (hasSettings) {
            hasRoot = true;
            dirs.addAll(includeDirs);
        } else {
            hasRoot = buildFiles.containsKey("");
            for (String dir : buildFiles.keySet()) {
                if (!dir.isEmpty()) {
                    dirs.add(dir);
                }
            }
        }
        if (!hasRoot && dirs.isEmpty()) {
            return;
        }

        Map<String, String> nameByDir = new LinkedHashMap<>();
        if (hasRoot) {
            String name = rootName != null ? rootName : defaultRootName;
            while (dirs.contains(name)) {
                name = name + "#root";
            }
            nameByDir.put("", name);
        }
        for (String dir : dirs) {
            nameByDir.put(dir, dir);
        }

        db.inTransaction(() -> {
            Map<String, Long> idByDir = new LinkedHashMap<>();
            try (PreparedStatement insert = db.connection().prepareStatement(
                    "INSERT INTO gradle_modules(name, dir, build_file) VALUES (?,?,?)")) {
                for (Map.Entry<String, String> entry : nameByDir.entrySet()) {
                    insert.setString(1, entry.getValue());
                    insert.setString(2, entry.getKey().isEmpty() ? "." : entry.getKey());
                    insert.setString(3, buildFiles.get(entry.getKey()));
                    insert.executeUpdate();
                    idByDir.put(entry.getKey(), db.queryLong("SELECT last_insert_rowid()"));
                }
            }
            try (PreparedStatement insert = db.connection().prepareStatement(
                    "INSERT INTO gradle_deps(module_id, config, dep_kind, target, line) VALUES (?,?,?,?,?)")) {
                for (Dep dep : deps) {
                    String owner = nearestModule(dep.moduleDir(), nameByDir.keySet());
                    if (owner == null) {
                        continue;
                    }
                    insert.setLong(1, idByDir.get(owner));
                    insert.setString(2, dep.row().config());
                    insert.setString(3, dep.row().depKind());
                    insert.setString(4, dep.row().target());
                    insert.setInt(5, dep.row().line());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            List<String> byDepth = new ArrayList<>(nameByDir.keySet());
            byDepth.sort(Comparator.comparingInt(String::length));
            try (PreparedStatement all = db.connection().prepareStatement("UPDATE files SET module = ?");
                 PreparedStatement prefix = db.connection().prepareStatement(
                         "UPDATE files SET module = ? WHERE substr(path, 1, ?) = ?")) {
                for (String dir : byDepth) {
                    if (dir.isEmpty()) {
                        all.setString(1, nameByDir.get(dir));
                        all.executeUpdate();
                    } else {
                        String with = dir + "/";
                        prefix.setString(1, nameByDir.get(dir));
                        prefix.setInt(2, with.length());
                        prefix.setString(3, with);
                        prefix.executeUpdate();
                    }
                }
            }
        });
    }

    private static String nearestModule(String dir, Set<String> modules) {
        String current = dir;
        while (true) {
            if (modules.contains(current)) {
                return current;
            }
            if (current.isEmpty()) {
                return null;
            }
            int slash = current.lastIndexOf('/');
            current = slash < 0 ? "" : current.substring(0, slash);
        }
    }

    private static String parentDir(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }
}