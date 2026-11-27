package ru.mcs.mcpsql.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatchGuardTest {

    private static final long MB = 1024L * 1024L;

    private static final String HEADER = "-- indexer-patch: 1\n-- db_id: abc\n-- from_seq: 1\n-- to_seq: 2\n"
            + "-- schema_version: 2\n";

    private static final String GENUINE = HEADER + "BEGIN;\n"
            + "PRAGMA defer_foreign_keys = ON;\n"
            + "CREATE TEMP TABLE _patch_guard(ok INTEGER NOT NULL CHECK (ok = 1));\n"
            + "INSERT INTO _patch_guard SELECT (SELECT value FROM meta WHERE scope = '' AND key = 'db_id') = 'abc' "
            + "AND (SELECT value FROM meta WHERE scope = '' AND key = 'seq') = '1' "
            + "AND (SELECT value FROM meta WHERE scope = '' AND key = 'schema_version') = '2';\n"
            + "DROP TABLE _patch_guard;\n"
            + "INSERT INTO code_fts(code_fts, rowid, path, content) SELECT 'delete', id, path, content FROM files "
            + "WHERE id IN (1, 2, 3);\n"
            + "DELETE FROM calls WHERE id = 7;\n"
            + "DELETE FROM member_tags WHERE member_id = 4 AND tag = 'it''s; fine -- really';\n"
            + "INSERT INTO files(id, path, content, size, hash) VALUES (9, 'a/B.java', "
            + "'class A { String s = \"x\"; } -- not a comment; /* nor this */', 12, X'0AFF');\n"
            + "INSERT INTO types(id, name, score, note) VALUES (1, 'T', -2.5, NULL);\n"
            + "INSERT INTO nums(id, v) VALUES (3, 9.0e+999);\n"
            + "INSERT INTO nums(id, v) VALUES (4, 1e-05);\n"
            + "UPDATE files SET path = 'b', size = 5 WHERE id = 9;\n"
            + "INSERT INTO code_fts(rowid, path, content) SELECT id, path, content FROM files WHERE id IN (9);\n"
            + "-- stats: deletes=2 inserts=3 updates=1\n"
            + "COMMIT;\n";

    @TempDir
    Path tmp;

    private int sanitize(String script, boolean gzip, long max) throws IOException {
        Path source = tmp.resolve("patch.in");
        byte[] bytes = script.getBytes(StandardCharsets.UTF_8);
        if (gzip) {
            try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(source))) {
                out.write(bytes);
            }
        } else {
            Files.write(source, bytes);
        }
        return PatchGuard.sanitize(source, tmp.resolve("patch.out"), max);
    }

    private void assertRefused(String statement) {
        String script = HEADER + "BEGIN;\n" + statement + ";\nCOMMIT;\n";
        assertThatThrownBy(() -> sanitize(script, true, MB)).as(statement)
                .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("patch rejected");
    }

    @Test
    void aGenuinePatchPassesAndIsWrittenUnchanged() throws IOException {
        assertThat(sanitize(GENUINE, true, MB)).isEqualTo(15);
        assertThat(Files.readString(tmp.resolve("patch.out"))).isEqualTo(GENUINE);

        assertThat(sanitize(GENUINE, false, MB)).isEqualTo(15);
    }

    @Test
    void everythingThatIsNotAPatchStatementIsRefused() {
        List<String> bad = List.of(
                "ATTACH DATABASE '/data/system/user.db' AS u",
                "attach database 'x' as y",
                "VACUUM INTO '/tmp/x.db'",
                "INSERT INTO files SELECT * FROM u.files",
                "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c) SELECT count(*) FROM c",
                "CREATE TRIGGER t AFTER INSERT ON files BEGIN SELECT 1",
                "CREATE TABLE x(a)",
                "DROP TABLE files",
                "PRAGMA writable_schema = ON",
                "UPDATE sqlite_master SET sql = 'x' WHERE name = 'files'",
                "DELETE FROM files WHERE id IN (SELECT id FROM files)",
                "DELETE FROM files",
                "DELETE FROM files WHERE id = 1 OR 1 = 1",
                "SELECT load_extension('x')",
                "INSERT INTO \"files\"(id) VALUES (1)",
                "DELETE FROM [files] WHERE id = 1",
                "DELETE FROM `files` WHERE id = 1",
                "INSERT INTO files(id) VALUES (1) /* x */",
                "INSERT INTO files(id) VALUES (abs(1))",
                "INSERT INTO files(id) VALUES (1), (2)",
                "UPDATE files SET size = size + 1 WHERE id = 1",
                "REPLACE INTO files(id) VALUES (1)",
                "INSERT INTO _patch_guard(ok) VALUES (1)",
                "INSERT INTO files(id, path) VALUES (1, 'a'); ATTACH DATABASE 'x' AS y");
        for (String statement : bad) {
            assertRefused(statement);
        }
    }

    @Test
    void aCompressionBombStopsAtTheSizeLimit() throws IOException {
        Path source = tmp.resolve("bomb.gz");
        byte[] line = "-- padding padding padding padding padding padding padding padding\n"
                .getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(source))) {
            for (long written = 0; written < 80 * MB; written += line.length) {
                out.write(line);
            }
        }
        assertThat(Files.size(source)).isLessThan(2 * MB);

        assertThatThrownBy(() -> PatchGuard.sanitize(source, tmp.resolve("bomb.out"), MB))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("larger than 1 MB");
        assertThat(Files.size(tmp.resolve("bomb.out"))).isLessThanOrEqualTo(MB + 1024);
    }

    @Test
    void brokenInputIsRefusedAsAPatchError() throws IOException {
        assertThatThrownBy(() -> sanitize(HEADER + "BEGIN;\nINSERT INTO t(a) VALUES ('abc", false, MB))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not terminated");

        Path broken = tmp.resolve("broken.gz");
        Files.write(broken, new byte[]{0x1f, (byte) 0x8b, 8, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10});
        assertThatThrownBy(() -> PatchGuard.sanitize(broken, tmp.resolve("broken.out"), MB))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("gzip");
    }

    @Test
    void aVeryLongStatementIsRefused() {
        String values = String.join(", ", java.util.Collections.nCopies(3000, "1"));
        assertRefused("INSERT INTO t(a) VALUES (" + values + ")");
    }
}
