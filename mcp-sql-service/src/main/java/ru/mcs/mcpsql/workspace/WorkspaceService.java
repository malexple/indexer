package ru.mcs.mcpsql.workspace;

import org.sqlite.SQLiteConfig;
import org.springframework.stereotype.Service;
import ru.mcs.indexer.db.ProjectNames;
import ru.mcs.indexer.db.Schema;
import ru.mcs.indexer.patch.PatchApplier;
import ru.mcs.mcpsql.config.ServiceProperties;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/** One SQLite file per workspace in the data directory; every replacement is an atomic rename. */
@Service
public class WorkspaceService {

    private final Path dir;
    private final long maxUploadBytes;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public WorkspaceService(ServiceProperties properties) throws IOException {
        this.dir = properties.dataDir().toAbsolutePath().normalize();
        this.maxUploadBytes = properties.maxUploadMb() * 1024L * 1024L;
        Files.createDirectories(dir);
    }

    public Path dbPath(String workspace) {
        if (!ProjectNames.isValid(workspace)) {
            throw new IllegalArgumentException("invalid workspace name: " + workspace);
        }
        return dir.resolve(workspace + ".db");
    }

    public List<String> list() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".db") && !n.startsWith("."))
                    .map(n -> n.substring(0, n.length() - 3))
                    .sorted().toList();
        }
    }

    /** The requested workspace, or the only one that exists. */
    public String resolve(String requested) {
        try {
            if (requested != null && !requested.isBlank()) {
                String workspace = requested.strip();
                if (!Files.isRegularFile(dbPath(workspace))) {
                    throw new NoSuchElementException("workspace not found: " + workspace + " (available: " + list() + ")");
                }
                return workspace;
            }
            List<String> all = list();
            if (all.size() == 1) {
                return all.get(0);
            }
            throw new IllegalArgumentException(all.isEmpty()
                    ? "no workspace has been uploaded yet"
                    : "several workspaces exist, pass the 'workspace' argument: " + all);
        } catch (IOException e) {
            throw new IllegalStateException("cannot list workspaces: " + e.getMessage(), e);
        }
    }

    public Connection openReadOnly(String workspace) throws SQLException {
        return open(dbPath(workspace));
    }

    public WorkspaceInfo info(String workspace) throws SQLException {
        Path file = dbPath(workspace);
        if (!Files.isRegularFile(file)) {
            throw new NoSuchElementException("workspace not found: " + workspace);
        }
        return infoOf(workspace, file);
    }

    /** Replaces the whole workspace with an uploaded index.db (plain or gzip). */
    public WorkspaceInfo putDatabase(String workspace, InputStream body) throws IOException, SQLException {
        Path target = dbPath(workspace);
        Path upload = temp();
        Path ready = upload;
        try {
            copyLimited(body, upload);
            ready = gunzipIfNeeded(upload);
            ensureIndexerDatabase(ready);
            synchronized (lock(workspace)) {
                Files.move(ready, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            return infoOf(workspace, target);
        } finally {
            Files.deleteIfExists(upload);
            Files.deleteIfExists(ready);
        }
    }

    /** Applies patch-N-M.sql(.gz) to a copy and swaps it in only when the whole patch went through. */
    public PatchApplier.Result applyPatch(String workspace, InputStream body) throws IOException, SQLException {
        Path target = dbPath(workspace);
        Path patch = temp();
        Path work = temp();
        try {
            copyLimited(body, patch);
            synchronized (lock(workspace)) {
                if (!Files.isRegularFile(target)) {
                    throw new NoSuchElementException("workspace not found: " + workspace + "; upload the database first");
                }
                Files.copy(target, work, StandardCopyOption.REPLACE_EXISTING);
                PatchApplier.Result result = PatchApplier.apply(work, patch);
                Files.move(work, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return result;
            }
        } finally {
            Files.deleteIfExists(patch);
            Files.deleteIfExists(work);
        }
    }

    private Object lock(String workspace) {
        return locks.computeIfAbsent(workspace, k -> new Object());
    }

    private Path temp() {
        return dir.resolve(".tmp-" + UUID.randomUUID() + ".part");
    }

    private void copyLimited(InputStream in, Path target) throws IOException {
        try (OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[1 << 16];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > maxUploadBytes) {
                    throw new IllegalArgumentException("upload is larger than " + maxUploadBytes / (1024 * 1024) + " MB");
                }
                out.write(buffer, 0, read);
            }
        }
    }

    private Path gunzipIfNeeded(Path file) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(2);
        }
        if (head.length < 2 || (head[0] & 0xff) != 0x1f || (head[1] & 0xff) != 0x8b) {
            return file;
        }
        Path plain = temp();
        try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            copyLimited(in, plain);
        }
        return plain;
    }

    private static void ensureIndexerDatabase(Path file) {
        try (Connection c = open(file)) {
            String version = scalar(c, "SELECT value FROM meta WHERE scope = '' AND key = 'schema_version'");
            if (!String.valueOf(Schema.VERSION).equals(version)) {
                throw new IllegalArgumentException("unsupported schema_version '" + version + "', expected "
                        + Schema.VERSION + "; re-index with the current Indexer");
            }
            if (scalar(c, "SELECT value FROM meta WHERE scope = '' AND key = 'db_id'") == null) {
                throw new IllegalArgumentException("not an Indexer database (no db_id)");
            }
            String check = scalar(c, "PRAGMA quick_check");
            if (!"ok".equals(check)) {
                throw new IllegalArgumentException("database failed quick_check: " + check);
            }
        } catch (SQLException e) {
            throw new IllegalArgumentException("not an Indexer database: " + e.getMessage(), e);
        }
    }

    private static WorkspaceInfo infoOf(String workspace, Path file) throws SQLException {
        try (Connection c = open(file)) {
            String dbId = scalar(c, "SELECT value FROM meta WHERE scope = '' AND key = 'db_id'");
            String seq = scalar(c, "SELECT value FROM meta WHERE scope = '' AND key = 'seq'");
            List<String> projects = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT slug FROM projects ORDER BY slug")) {
                while (rs.next()) {
                    projects.add(rs.getString(1));
                }
            }
            return new WorkspaceInfo(workspace, dbId, seq == null ? -1 : Long.parseLong(seq), projects);
        }
    }

    private static Connection open(Path file) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file, config.toProperties());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA query_only = ON");
        }
        return connection;
    }

    private static String scalar(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}