package ru.mcs.indexer.patch;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

/**
 * Builds a patch (SQL script) that turns an older index database into a newer one. Works on any table found in
 * sqlite_master (primary keys and foreign keys are read from the schema), so new languages need no changes here.
 * The code_fts table is maintained explicitly for the files whose path or text changed.
 */
public final class PatchWriter {

    public record Result(Path file, long fromSeq, long toSeq, int deletes, int inserts, int updates, long bytes) {
    }

    private record Table(String name, List<String> columns, List<String> primaryKey) {
    }

    private static final class Counts {
        int deletes;
        int inserts;
        int updates;
    }

    private static final int ID_CHUNK = 500;

    private PatchWriter() {
    }

    /** Writes patch-FROM-TO.sql.gz into dir; empty when the two databases are at the same seq. */
    public static Optional<Result> write(Path oldDb, Path newDb, Path dir) throws IOException, SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + newDb.toAbsolutePath());
             Statement st = connection.createStatement()) {
            st.execute("ATTACH DATABASE " + literal(oldDb.toAbsolutePath().toString()) + " AS old");
            String dbId = meta(st, "main", "db_id");
            String schema = meta(st, "main", "schema_version");
            if (!Objects.equals(dbId, meta(st, "old", "db_id"))) {
                throw new IllegalStateException("the databases have different db_id, a patch cannot be built");
            }
            if (!Objects.equals(schema, meta(st, "old", "schema_version"))) {
                throw new IllegalStateException("the databases have different schema_version, upload the full database");
            }
            long from = Long.parseLong(Objects.requireNonNull(meta(st, "old", "seq"), "old seq"));
            long to = Long.parseLong(Objects.requireNonNull(meta(st, "main", "seq"), "new seq"));
            if (to == from) {
                return Optional.empty();
            }
            if (to < from) {
                throw new IllegalStateException("the new database has a lower seq (" + to + ") than the base (" + from + ")");
            }
            Files.createDirectories(dir);
            Path target = dir.resolve("patch-" + from + "-" + to + ".sql.gz");
            Path part = dir.resolve(target.getFileName() + ".part");
            Counts counts = new Counts();
            try (Writer writer = new BufferedWriter(new OutputStreamWriter(
                    new GZIPOutputStream(Files.newOutputStream(part)), StandardCharsets.UTF_8), 1 << 16)) {
                emit(st, writer, dbId, schema, from, to, counts);
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            return Optional.of(new Result(target, from, to, counts.deletes, counts.inserts, counts.updates,
                    Files.size(target)));
        }
    }

    private static void emit(Statement st, Writer w, String dbId, String schema, long from, long to, Counts counts)
            throws IOException, SQLException {
        List<Table> tables = tablesInOrder(st);

        line(w, "-- indexer-patch: 1");
        line(w, "-- db_id: " + dbId);
        line(w, "-- from_seq: " + from);
        line(w, "-- to_seq: " + to);
        line(w, "-- schema_version: " + schema);
        line(w, "BEGIN;");
        line(w, "PRAGMA defer_foreign_keys = ON;");
        line(w, "CREATE TEMP TABLE _patch_guard(ok INTEGER NOT NULL CHECK (ok = 1));");
        line(w, "INSERT INTO _patch_guard SELECT " + metaCheck("db_id", dbId) + " AND "
                + metaCheck("seq", String.valueOf(from)) + " AND " + metaCheck("schema_version", schema) + ";");
        line(w, "DROP TABLE _patch_guard;");

        List<Long> removed = ids(st, "SELECT o.id FROM old.files o WHERE NOT EXISTS "
                + "(SELECT 1 FROM main.files n WHERE n.id = o.id)");
        List<Long> added = ids(st, "SELECT n.id FROM main.files n WHERE NOT EXISTS "
                + "(SELECT 1 FROM old.files o WHERE o.id = n.id)");
        List<Long> changed = ids(st, "SELECT n.id FROM main.files n JOIN old.files o ON o.id = n.id "
                + "WHERE n.path IS NOT o.path OR n.content IS NOT o.content");
        Set<Long> ftsDelete = new TreeSet<>(removed);
        ftsDelete.addAll(changed);
        Set<Long> ftsInsert = new TreeSet<>(added);
        ftsInsert.addAll(changed);

        for (List<Long> chunk : chunks(ftsDelete)) {
            line(w, "INSERT INTO code_fts(code_fts, rowid, path, content) SELECT 'delete', id, path, content "
                    + "FROM files WHERE id IN (" + join(chunk) + ");");
        }

        for (int i = tables.size() - 1; i >= 0; i--) {
            Table t = tables.get(i);
            String sql = "SELECT " + quotes("o", t.primaryKey()) + " FROM old." + t.name() + " o WHERE NOT EXISTS "
                    + "(SELECT 1 FROM main." + t.name() + " n WHERE " + match("n", "o", t.primaryKey()) + ") "
                    + "ORDER BY " + columns("o", t.primaryKey());
            try (ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    line(w, "DELETE FROM " + t.name() + " WHERE " + where(t.primaryKey(), rs, 1) + ";");
                    counts.deletes++;
                }
            }
        }

        for (Table t : tables) {
            String sql = "SELECT " + quotes("n", t.columns()) + " FROM main." + t.name() + " n WHERE NOT EXISTS "
                    + "(SELECT 1 FROM old." + t.name() + " o WHERE " + match("o", "n", t.primaryKey()) + ") "
                    + "ORDER BY " + columns("n", t.primaryKey());
            try (ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    List<String> values = new ArrayList<>();
                    for (int i = 1; i <= t.columns().size(); i++) {
                        values.add(rs.getString(i));
                    }
                    line(w, "INSERT INTO " + t.name() + "(" + String.join(", ", t.columns()) + ") VALUES ("
                            + String.join(", ", values) + ");");
                    counts.inserts++;
                }
            }
        }

        for (Table t : tables) {
            List<String> other = t.columns().stream().filter(c -> !t.primaryKey().contains(c)).toList();
            if (other.isEmpty()) {
                continue;
            }
            String differs = other.stream().map(c -> "n." + c + " IS NOT o." + c).collect(Collectors.joining(" OR "));
            String selected = other.stream()
                    .map(c -> "CASE WHEN n." + c + " IS NOT o." + c + " THEN quote(n." + c + ") END")
                    .collect(Collectors.joining(", "));
            String sql = "SELECT " + quotes("n", t.primaryKey()) + ", " + selected + " FROM main." + t.name()
                    + " n JOIN old." + t.name() + " o ON " + match("n", "o", t.primaryKey())
                    + " WHERE " + differs + " ORDER BY " + columns("n", t.primaryKey());
            try (ResultSet rs = st.executeQuery(sql)) {
                int pkSize = t.primaryKey().size();
                while (rs.next()) {
                    List<String> assignments = new ArrayList<>();
                    for (int i = 0; i < other.size(); i++) {
                        String value = rs.getString(pkSize + 1 + i);
                        if (value != null) {
                            assignments.add(other.get(i) + " = " + value);
                        }
                    }
                    line(w, "UPDATE " + t.name() + " SET " + String.join(", ", assignments) + " WHERE "
                            + where(t.primaryKey(), rs, 1) + ";");
                    counts.updates++;
                }
            }
        }

        for (List<Long> chunk : chunks(ftsInsert)) {
            line(w, "INSERT INTO code_fts(rowid, path, content) SELECT id, path, content FROM files "
                    + "WHERE id IN (" + join(chunk) + ");");
        }
        line(w, "-- stats: deletes=" + counts.deletes + " inserts=" + counts.inserts + " updates=" + counts.updates);
        line(w, "COMMIT;");
    }

    /** User tables, parents before children (from the foreign keys declared in the schema). */
    private static List<Table> tablesInOrder(Statement st) throws SQLException {
        List<String> names = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("SELECT name FROM main.sqlite_master WHERE type = 'table'")) {
            while (rs.next()) {
                String name = rs.getString(1);
                if (!name.startsWith("sqlite_") && !name.startsWith("code_fts")) {
                    names.add(name);
                }
            }
        }
        Collections.sort(names);
        Map<String, Set<String>> parents = new TreeMap<>();
        for (String name : names) {
            Set<String> set = new HashSet<>();
            try (ResultSet rs = st.executeQuery("PRAGMA main.foreign_key_list(" + name + ")")) {
                while (rs.next()) {
                    String parent = rs.getString("table");
                    if (!parent.equals(name) && names.contains(parent)) {
                        set.add(parent);
                    }
                }
            }
            parents.put(name, set);
        }
        List<String> ordered = new ArrayList<>();
        Set<String> done = new LinkedHashSet<>();
        while (ordered.size() < names.size()) {
            boolean progress = false;
            for (String name : names) {
                if (!done.contains(name) && done.containsAll(parents.get(name))) {
                    ordered.add(name);
                    done.add(name);
                    progress = true;
                }
            }
            if (!progress) {
                throw new IllegalStateException("cyclic foreign keys between tables: " + names);
            }
        }
        List<Table> tables = new ArrayList<>();
        for (String name : ordered) {
            List<String> columns = new ArrayList<>();
            TreeMap<Integer, String> primaryKey = new TreeMap<>();
            try (ResultSet rs = st.executeQuery("PRAGMA main.table_info(" + name + ")")) {
                while (rs.next()) {
                    columns.add(rs.getString("name"));
                    int pk = rs.getInt("pk");
                    if (pk > 0) {
                        primaryKey.put(pk, rs.getString("name"));
                    }
                }
            }
            if (primaryKey.isEmpty()) {
                throw new IllegalStateException("table " + name + " has no primary key");
            }
            tables.add(new Table(name, columns, new ArrayList<>(primaryKey.values())));
        }
        return tables;
    }

    private static String meta(Statement st, String schema, String key) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT value FROM " + schema
                + ".meta WHERE scope = '' AND key = " + literal(key))) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static String metaCheck(String key, String value) {
        return "(SELECT value FROM meta WHERE scope = '' AND key = " + literal(key) + ") = " + literal(value);
    }

    private static List<Long> ids(Statement st, String sql) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        }
        return ids;
    }

    private static List<List<Long>> chunks(Set<Long> ids) {
        List<Long> all = new ArrayList<>(ids);
        List<List<Long>> chunks = new ArrayList<>();
        for (int i = 0; i < all.size(); i += ID_CHUNK) {
            chunks.add(all.subList(i, Math.min(all.size(), i + ID_CHUNK)));
        }
        return chunks;
    }

    private static String join(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    private static String quotes(String alias, List<String> columns) {
        return columns.stream().map(c -> "quote(" + alias + "." + c + ")").collect(Collectors.joining(", "));
    }

    private static String columns(String alias, List<String> columns) {
        return columns.stream().map(c -> alias + "." + c).collect(Collectors.joining(", "));
    }

    private static String match(String a, String b, List<String> columns) {
        return columns.stream().map(c -> a + "." + c + " = " + b + "." + c).collect(Collectors.joining(" AND "));
    }

    private static String where(List<String> primaryKey, ResultSet rs, int firstColumn) throws SQLException {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < primaryKey.size(); i++) {
            parts.add(primaryKey.get(i) + " = " + rs.getString(firstColumn + i));
        }
        return String.join(" AND ", parts);
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static void line(Writer w, String text) throws IOException {
        w.write(text);
        w.write('\n');
    }
}