package ru.mcs.indexer.patch;

import ru.mcs.indexer.db.Db;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** Applies a patch built by {@link PatchWriter}: all or nothing, with the same checks as apply_patch.py. */
public final class PatchApplier {

    public record Result(long fromSeq, long toSeq) {
    }

    private static final Pattern HEADER_LINE = Pattern.compile("(?m)^-- ([a-z_]+): (.*)$");

    private PatchApplier() {
    }

    public static Result apply(Path dbPath, Path patch) throws IOException, SQLException {
        if (!Files.isRegularFile(dbPath)) {
            throw new IllegalArgumentException("database not found: " + dbPath);
        }
        if (!Files.isRegularFile(patch)) {
            throw new IllegalArgumentException("patch not found: " + patch);
        }
        byte[] raw = Files.readAllBytes(patch);
        if (raw.length > 2 && (raw[0] & 0xff) == 0x1f && (raw[1] & 0xff) == 0x8b) {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                raw = in.readAllBytes();
            }
        }
        String script = new String(raw, StandardCharsets.UTF_8);
        Map<String, String> header = header(script);
        for (String key : List.of("db_id", "schema_version", "from_seq", "to_seq")) {
            if (!header.containsKey(key)) {
                throw new IllegalArgumentException("not an Indexer patch (no '" + key + "' in the header): " + patch);
            }
        }

        try (Db db = Db.open(dbPath)) {
            check(db, "db_id", header.get("db_id"));
            check(db, "schema_version", header.get("schema_version"));
            check(db, "seq", header.get("from_seq"));
            List<String> statements = SqlScript.split(script);
            db.inTransaction(() -> {
                for (String statement : statements) {
                    String upper = statement.toUpperCase();
                    if (upper.equals("BEGIN") || upper.equals("COMMIT")) {
                        continue;
                    }
                    db.execute(statement);
                }
                verify(db);
            });
            return new Result(Long.parseLong(header.get("from_seq")),
                    Long.parseLong(db.getGlobalMeta("seq")));
        }
    }

    private static void check(Db db, String key, String expected) throws SQLException {
        String actual = db.getGlobalMeta(key);
        if (!expected.equals(actual)) {
            throw new IllegalStateException("REFUSED: database " + key + "='" + actual + "', patch expects '"
                    + expected + "'. Apply the missing patches first or use a fresh database"
                    + ("seq".equals(key) ? " (seq mismatch)." : "."));
        }
    }

    private static void verify(Db db) throws SQLException {
        try (Statement st = db.connection().createStatement();
             ResultSet rs = st.executeQuery("PRAGMA foreign_key_check")) {
            if (rs.next()) {
                throw new SQLException("foreign_key_check failed after the patch (table " + rs.getString(1) + ")");
            }
        }
        db.execute("INSERT INTO code_fts(code_fts, rank) VALUES ('integrity-check', 1)");
    }

    private static Map<String, String> header(String script) {
        int end = script.indexOf("\nBEGIN;");
        String head = end < 0 ? script.substring(0, Math.min(script.length(), 2000)) : script.substring(0, end);
        Map<String, String> map = new HashMap<>();
        Matcher m = HEADER_LINE.matcher(head);
        while (m.find()) {
            map.putIfAbsent(m.group(1), m.group(2).strip());
        }
        return map;
    }
}