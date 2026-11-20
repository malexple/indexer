package ru.mcs.indexer.db;

import ru.mcs.indexer.lang.FileFacts;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Collects Gradle facts of all files, then brings gradle_modules and gradle_deps of the current project in line
 * with them and refreshes files.module, in one transaction once every file is in the database. The tables are
 * derived data, recomputed on each run, but rewritten only when their logical content changed, so row ids stay
 * stable (and patches stay small). Dependencies are sorted for the same reason. files.module is touched only
 * where the value changes, because updating a row rewrites its (possibly large) content. Single writer thread only.
 */
final class GradleRegistry {

    private record Dep(String moduleDir, FileFacts.GradleDepRow row) {
    }

    private record FileModule(long id, String path, String module) {
    }

    private record ModuleRow(String name, String dir, String buildFile) {
    }

    private record DepRow(String module, String config, String depKind, String target, int line) {
    }

    private static final Comparator<DepRow> DEP_ORDER = Comparator
            .comparing(DepRow::module)
            .thenComparing(DepRow::config)
            .thenComparing(DepRow::depKind)
            .thenComparing(DepRow::target)
            .thenComparingInt(DepRow::line);

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

        List<ModuleRow> wantModules = new ArrayList<>();
        for (Map.Entry<String, String> entry : nameByDir.entrySet()) {
            wantModules.add(new ModuleRow(entry.getValue(), entry.getKey().isEmpty() ? "." : entry.getKey(),
                    buildFiles.get(entry.getKey())));
        }
        List<DepRow> wantDeps = new ArrayList<>();
        for (Dep dep : deps) {
            String owner = nearestModule(dep.moduleDir(), nameByDir.keySet());
            if (owner == null) {
                continue;
            }
            FileFacts.GradleDepRow row = dep.row();
            wantDeps.add(new DepRow(nameByDir.get(owner), row.config(), row.depKind(), row.target(), row.line()));
        }
        wantDeps.sort(DEP_ORDER);

        db.inTransaction(() -> {
            if (!wantModules.equals(readModules(db)) || !wantDeps.equals(readDeps(db))) {
                rewrite(db, wantModules, wantDeps);
            }
            refreshFileModules(db, nameByDir);
        });
    }

    private static List<ModuleRow> readModules(Db db) throws SQLException {
        List<ModuleRow> rows = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT name, dir, build_file FROM gradle_modules WHERE project_id = ? ORDER BY id")) {
            ps.setString(1, db.project());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new ModuleRow(rs.getString(1), rs.getString(2), rs.getString(3)));
                }
            }
        }
        return rows;
    }

    private static List<DepRow> readDeps(Db db) throws SQLException {
        List<DepRow> rows = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT m.name, d.config, d.dep_kind, d.target, d.line FROM gradle_deps d "
                        + "JOIN gradle_modules m ON m.id = d.module_id WHERE m.project_id = ? ORDER BY d.id")) {
            ps.setString(1, db.project());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new DepRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getInt(5)));
                }
            }
        }
        return rows;
    }

    private static void rewrite(Db db, List<ModuleRow> modules, List<DepRow> depRows) throws SQLException {
        String project = db.project();
        db.update("DELETE FROM gradle_deps WHERE module_id IN (SELECT id FROM gradle_modules WHERE project_id = ?)",
                project);
        db.update("DELETE FROM gradle_modules WHERE project_id = ?", project);
        Map<String, Long> idByName = new LinkedHashMap<>();
        try (PreparedStatement insert = db.connection().prepareStatement(
                "INSERT INTO gradle_modules(project_id, name, dir, build_file) VALUES (?,?,?,?)")) {
            for (ModuleRow module : modules) {
                insert.setString(1, project);
                insert.setString(2, module.name());
                insert.setString(3, module.dir());
                insert.setString(4, module.buildFile());
                insert.executeUpdate();
                idByName.put(module.name(), db.queryLong("SELECT last_insert_rowid()"));
            }
        }
        try (PreparedStatement insert = db.connection().prepareStatement(
                "INSERT INTO gradle_deps(module_id, config, dep_kind, target, line) VALUES (?,?,?,?,?)")) {
            for (DepRow dep : depRows) {
                insert.setLong(1, idByName.get(dep.module()));
                insert.setString(2, dep.config());
                insert.setString(3, dep.depKind());
                insert.setString(4, dep.target());
                insert.setInt(5, dep.line());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static void refreshFileModules(Db db, Map<String, String> nameByDir) throws SQLException {
        List<FileModule> files = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT id, path, module FROM files WHERE project_id = ? ORDER BY id")) {
            ps.setString(1, db.project());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    files.add(new FileModule(rs.getLong(1), rs.getString(2), rs.getString(3)));
                }
            }
        }
        try (PreparedStatement update = db.connection().prepareStatement("UPDATE files SET module = ? WHERE id = ?")) {
            for (FileModule file : files) {
                String owner = nearestModule(parentDir(file.path()), nameByDir.keySet());
                String target = owner == null ? null : nameByDir.get(owner);
                if (Objects.equals(target, file.module())) {
                    continue;
                }
                if (target == null) {
                    update.setNull(1, java.sql.Types.VARCHAR);
                } else {
                    update.setString(1, target);
                }
                update.setLong(2, file.id());
                update.addBatch();
            }
            update.executeBatch();
        }
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