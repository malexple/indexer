package ru.mcs.mcpsql.workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.mcs.indexer.db.Db;
import ru.mcs.indexer.db.Schema;
import ru.mcs.indexer.patch.PatchApplier;
import ru.mcs.indexer.patch.PatchWriter;
import ru.mcs.mcpsql.config.MultiUserProperties;
import ru.mcs.mcpsql.config.ServiceProperties;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceServicePatchGuardTest {

    @TempDir
    Path tmp;

    private WorkspaceService service;

    @BeforeEach
    void setUp() throws IOException {
        ServiceProperties properties = new ServiceProperties(tmp.resolve("data"), "r", "w", 8000, 3, 200, 50);
        service = new WorkspaceService(properties,
                MultiUserProperties.of(true, 50, 5).withMinFreeDiskMb(0).withMaxPatchSqlMb(1));
    }

    private Path indexDb(String name, long seq, Map<Integer, String> files, String... extraDdl) throws Exception {
        Path file = tmp.resolve(name);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE meta (scope TEXT NOT NULL, key TEXT NOT NULL, value TEXT, PRIMARY KEY (scope, key))");
            s.execute("INSERT INTO meta VALUES ('', 'schema_version', '" + Schema.VERSION + "')");
            s.execute("INSERT INTO meta VALUES ('', 'db_id', 'patch-db')");
            s.execute("INSERT INTO meta VALUES ('', 'seq', '" + seq + "')");
            s.execute("CREATE TABLE projects (slug TEXT PRIMARY KEY)");
            s.execute("INSERT INTO projects VALUES ('demo')");
            s.execute("CREATE TABLE files (id INTEGER PRIMARY KEY, path TEXT NOT NULL, content TEXT)");
            s.execute("CREATE VIRTUAL TABLE code_fts USING fts5(path, content, content='files', content_rowid='id')");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO files(id, path, content) VALUES (?, ?, ?)")) {
                for (Map.Entry<Integer, String> e : files.entrySet()) {
                    ps.setInt(1, e.getKey());
                    ps.setString(2, "src/F" + e.getKey() + ".java");
                    ps.setString(3, e.getValue());
                    ps.executeUpdate();
                }
            }
            s.execute("INSERT INTO code_fts(code_fts) VALUES ('rebuild')");
            for (String ddl : extraDdl) {
                s.execute(ddl);
            }
        }
        return file;
    }

    private void upload(String workspace, Path db) throws Exception {
        try (InputStream in = Files.newInputStream(db)) {
            service.putDatabase(workspace, in);
        }
    }

    private static byte[] gzip(String text) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (OutputStream out = new GZIPOutputStream(bytes)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private Path realDb(String name, long seq, Map<String, String> files) throws Exception {
        Path file = tmp.resolve(name);
        try (Db db = Db.open(file)) {
            db.useProject("demo");
            for (Map.Entry<String, String> e : files.entrySet()) {
                insertFile(db, e.getKey(), e.getValue());
            }
            db.execute("INSERT INTO code_fts(code_fts) VALUES ('rebuild')");
            db.update("UPDATE meta SET value = ? WHERE scope = '' AND key = 'seq'", String.valueOf(seq));
        }
        return file;
    }

    private static void insertFile(Db db, String path, String content) throws Exception {
        db.update("INSERT INTO files(project_id, path, module, lang, kind, size_bytes, line_count, sha256, content, "
                + "indexed_at) VALUES ('demo', ?, NULL, 'java', 'SOURCE', ?, 1, 'x', ?, '2026-01-01T00:00:00Z')",
                path, content.length(), content);
    }

    @Test
    void aRealPatchFromThePatchWriterIsAccepted() throws Exception {
        Path oldDb = realDb("old.db", 1, new TreeMap<>(Map.of(
                "src/A.java", "class A", "src/B.java", "class B", "src/C.java", "class C")));
        Path newDb = tmp.resolve("new.db");
        Files.copy(oldDb, newDb);
        try (Db db = Db.open(newDb)) {
            db.useProject("demo");
            db.update("UPDATE files SET content = ?, size_bytes = 40 WHERE path = 'src/B.java'",
                    "class B changed 'quoted'; -- dashes");
            db.update("DELETE FROM files WHERE path = 'src/C.java'");
            insertFile(db, "src/D.java", "class D");
            db.execute("INSERT INTO code_fts(code_fts) VALUES ('rebuild')");
            db.update("UPDATE meta SET value = '2' WHERE scope = '' AND key = 'seq'");
        }
        Path patch = PatchWriter.write(oldDb, newDb, tmp.resolve("patches")).orElseThrow().file();
        upload("1/demo", oldDb);

        PatchApplier.Result result;
        try (InputStream in = Files.newInputStream(patch)) {
            result = service.applyPatch("1/demo", in);
        }

        assertThat(result.fromSeq()).isEqualTo(1);
        assertThat(result.toSeq()).isEqualTo(2);
        assertThat(service.info("1/demo").seq()).isEqualTo(2);
        try (Connection c = service.openReadOnly("1/demo");
             Statement s = c.createStatement();
             java.sql.ResultSet rs = s.executeQuery("SELECT group_concat(path || '=' || content, '|') FROM "
                     + "(SELECT path, content FROM files ORDER BY path)")) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo(
                    "src/A.java=class A|src/B.java=class B changed 'quoted'; -- dashes|src/D.java=class D");
        }
    }

    @Test
    void aPatchThatReachesForOtherFilesIsRefusedAndNothingChanges() throws Exception {
        upload("1/demo", indexDb("base.db", 1, Map.of(1, "class A")));
        String evil = "-- indexer-patch: 1\n-- db_id: patch-db\n-- from_seq: 1\n-- to_seq: 2\n-- schema_version: "
                + Schema.VERSION + "\nBEGIN;\nATTACH DATABASE '" + tmp.resolve("data/system/user.db").toString().replace('\\', '/')
                + "' AS stolen;\nINSERT INTO files(id, path, content) SELECT id, 'x', 'y' FROM stolen.users;\nCOMMIT;\n";

        assertThatThrownBy(() -> service.applyPatch("1/demo", new ByteArrayInputStream(gzip(evil))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("patch rejected");

        assertThat(service.info("1/demo").seq()).isEqualTo(1);
        try (Stream<Path> files = Files.list(tmp.resolve("data"))) {
            assertThat(files.filter(p -> p.getFileName().toString().endsWith(".part")).toList()).isEmpty();
        }
    }

    @Test
    void aCompressionBombIsRefusedWithoutHarm() throws Exception {
        upload("1/demo", indexDb("base.db", 1, Map.of(1, "class A")));
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] line = "-- padding padding padding padding padding padding padding padding\n".getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = new GZIPOutputStream(bytes)) {
            for (long written = 0; written < 100L * 1024 * 1024; written += line.length) {
                out.write(line);
            }
        }

        assertThatThrownBy(() -> service.applyPatch("1/demo", new ByteArrayInputStream(bytes.toByteArray())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("larger than 1 MB");
        assertThat(service.info("1/demo").seq()).isEqualTo(1);
    }

    @Test
    void databasesWithViewsTriggersOrForeignTablesAreRefusedOnUpload() throws Exception {
        Path withTrigger = indexDb("trigger.db", 1, Map.of(1, "a"),
                "CREATE TRIGGER evil AFTER INSERT ON files BEGIN SELECT 1; END");
        Path withView = indexDb("view.db", 1, Map.of(1, "a"), "CREATE VIEW sneaky AS SELECT * FROM files");
        Path withModule = indexDb("module.db", 1, Map.of(1, "a"), "CREATE VIRTUAL TABLE evil USING fts5(a)");

        assertThatThrownBy(() -> upload("1/t", withTrigger)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trigger");
        assertThatThrownBy(() -> upload("1/v", withView)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("view");
        assertThatThrownBy(() -> upload("1/m", withModule)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("virtual table");
        assertThat(service.list(1)).isEmpty();

        upload("1/ok", indexDb("ok.db", 1, Map.of(1, "a")));
        assertThat(service.list(1)).containsExactly("ok");
    }
}
