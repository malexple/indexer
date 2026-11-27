package ru.mcs.mcpsql.workspace;

import org.sqlite.SQLiteConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.mcs.indexer.db.ProjectNames;
import ru.mcs.indexer.db.Schema;
import ru.mcs.indexer.patch.PatchApplier;
import ru.mcs.mcpsql.config.MultiUserProperties;
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

/**
 * One SQLite file per workspace; every replacement is an atomic rename. In local mode the files lie in the
 * data directory. In multi-user mode they lie in users/{id}/ and every workspace is addressed as "id/name".
 */
@Service
public class WorkspaceService {

    private static final long MB = 1024L * 1024L;

    private final Path dir;
    private final long maxUploadBytes;
    private final boolean multi;
    private final int maxWorkspaces;
    private final long maxDbBytes;
    private final long minFreeBytes;
    private final long maxPatchSqlBytes;
    private final java.util.concurrent.Semaphore patchSlots = new java.util.concurrent.Semaphore(2);
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    @Autowired
    public WorkspaceService(ServiceProperties properties, MultiUserProperties multiProperties) throws IOException {
        this.dir = properties.dataDir().toAbsolutePath().normalize();
        this.maxUploadBytes = properties.maxUploadMb() * MB;
        this.multi = multiProperties.enabled();
        this.maxWorkspaces = multiProperties.maxWorkspaces();
        this.maxDbBytes = multiProperties.maxDbMb() * MB;
        this.minFreeBytes = multi ? multiProperties.minFreeDiskMb() * MB : 0;
        this.maxPatchSqlBytes = multiProperties.maxPatchSqlMb() * MB;
        Files.createDirectories(dir);
    }

    public WorkspaceService(ServiceProperties properties) throws IOException {
        this(properties, MultiUserProperties.of(false, 50, 5));
    }

    public boolean multi() {
        return multi;
    }

    public Path dbPath(String workspace) {
        return dbPath(WorkspaceKey.parse(workspace));
    }

    private Path dbPath(WorkspaceKey key) {
        String name = key.name();
        if (!ProjectNames.isValid(name) || name.contains("/") || name.contains("\\") || name.startsWith(".")) {
            throw new IllegalArgumentException("invalid workspace name: " + name);
        }
        if (key.owner() == null) {
            if (multi) {
                throw new IllegalArgumentException("invalid workspace name: " + name);
            }
            return dir.resolve(name + ".db");
        }
        if (!multi) {
            throw new IllegalArgumentException("invalid workspace name: " + key.asString());
        }
        Path userDir = userDir(key.owner());
        Path file = userDir.resolve(name + ".db").normalize();
        if (!file.startsWith(userDir)) {
            throw new IllegalArgumentException("invalid workspace name: " + name);
        }
        return file;
    }

    private Path userDir(long owner) {
        return dir.resolve("users").resolve(Long.toString(owner));
    }

    /** Names of the workspaces in the data directory (local mode). */
    public List<String> list() throws IOException {
        return names(dir);
    }

    /** Names of one user's workspaces (multi-user mode). */
    public List<String> list(long owner) throws IOException {
        Path userDir = userDir(owner);
        return Files.isDirectory(userDir) ? names(userDir) : List.of();
    }

    private static List<String> names(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".db") && !n.startsWith("."))
                    .map(n -> n.substring(0, n.length() - 3))
                    .sorted().toList();
        }
    }

    /** The requested workspace, or the only one that exists. */
    public String resolve(String requested) {
        try {
            WorkspaceKey key = WorkspaceKey.parse(requested);
            if (multi && key.owner() == null) {
                throw new IllegalArgumentException("a workspace must be addressed through a user");
            }
            List<String> names = key.owner() == null ? list() : list(key.owner());
            if (!key.name().isBlank()) {
                if (!Files.isRegularFile(dbPath(key))) {
                    throw new NoSuchElementException("workspace not found: " + key.name() + " (available: " + names + ")");
                }
                return key.asString();
            }
            if (names.size() == 1) {
                return new WorkspaceKey(key.owner(), names.get(0)).asString();
            }
            throw new IllegalArgumentException(names.isEmpty()
                    ? "no workspace has been uploaded yet"
                    : "several workspaces exist, pass the 'workspace' argument: " + names);
        } catch (IOException e) {
            throw new IllegalStateException("cannot list workspaces: " + e.getMessage(), e);
        }
    }

    /** The workspace name without the owner prefix, as the user knows it. */
    public String displayName(String workspace) {
        return WorkspaceKey.parse(workspace).name();
    }

    public Connection openReadOnly(String workspace) throws SQLException {
        return open(dbPath(workspace));
    }

    public WorkspaceInfo info(String workspace) throws SQLException {
        Path file = dbPath(workspace);
        if (!Files.isRegularFile(file)) {
            throw new NoSuchElementException("workspace not found: " + displayName(workspace));
        }
        return infoOf(displayName(workspace), file);
    }

    public long sizeBytes(String workspace) throws IOException {
        Path file = dbPath(workspace);
        if (!Files.isRegularFile(file)) {
            throw new NoSuchElementException("workspace not found: " + displayName(workspace));
        }
        return Files.size(file);
    }

    /** Replaces the whole workspace with an uploaded index.db (plain or gzip). */
    public WorkspaceInfo putDatabase(String workspace, InputStream body) throws IOException, SQLException {
        WorkspaceKey key = WorkspaceKey.parse(workspace);
        Path target = dbPath(key);
        long limit = limitFor(key);
        ensureFreeSpace();
        Path upload = temp();
        Path ready = upload;
        try {
            copyLimited(body, upload, limit);
            ready = gunzipIfNeeded(upload, limit);
            ensureIndexerDatabase(ready);
            synchronized (lockFor(key)) {
                if (key.owner() != null && !Files.isRegularFile(target)
                        && list(key.owner()).size() >= maxWorkspaces) {
                    throw new IllegalStateException("limit reached: at most " + maxWorkspaces
                            + " workspaces per user, delete one first");
                }
                Files.createDirectories(target.getParent());
                Files.move(ready, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            return infoOf(key.name(), target);
        } finally {
            Files.deleteIfExists(upload);
            Files.deleteIfExists(ready);
        }
    }

    /** Applies patch-N-M.sql(.gz) to a copy and swaps it in only when the whole patch went through. */
    public PatchApplier.Result applyPatch(String workspace, InputStream body) throws IOException, SQLException {
        WorkspaceKey key = WorkspaceKey.parse(workspace);
        Path target = dbPath(key);
        long limit = limitFor(key);
        ensureFreeSpace();
        Path patch = temp();
        Path work = temp();
        try {
            copyLimited(body, patch, limit);
            synchronized (lockFor(key)) {
                if (!Files.isRegularFile(target)) {
                    throw new NoSuchElementException("workspace not found: " + key.name() + "; upload the database first");
                }
                Files.copy(target, work, StandardCopyOption.REPLACE_EXISTING);
                PatchApplier.Result result = applyChecked(work, patch);
                if (key.owner() != null && Files.size(work) > maxDbBytes) {
                    throw new IllegalArgumentException("the database would be larger than " + maxDbBytes / MB
                            + " MB after this patch; the workspace is unchanged");
                }
                Files.move(work, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return result;
            }
        } finally {
            Files.deleteIfExists(patch);
            Files.deleteIfExists(work);
        }
    }

    /** In multi-user mode a patch is unpacked, checked and only then applied, two at a time. */
    private PatchApplier.Result applyChecked(Path work, Path patch) throws IOException, SQLException {
        if (!multi) {
            return PatchApplier.apply(work, patch);
        }
        boolean acquired;
        try {
            acquired = patchSlots.tryAcquire(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting to apply the patch");
        }
        if (!acquired) {
            throw new IllegalStateException("the server is busy applying other patches, try again in a moment");
        }
        Path clean = temp();
        try {
            PatchGuard.sanitize(patch, clean, maxPatchSqlBytes);
            return PatchApplier.apply(work, clean);
        } finally {
            patchSlots.release();
            Files.deleteIfExists(clean);
        }
    }

    public void delete(String workspace) throws IOException {
        WorkspaceKey key = WorkspaceKey.parse(workspace);
        Path target = dbPath(key);
        synchronized (lockFor(key)) {
            if (!Files.deleteIfExists(target)) {
                throw new NoSuchElementException("workspace not found: " + key.name());
            }
        }
    }

    /** Deletes every workspace of one user; returns how many files went away. */
    public int deleteAllOf(long owner) throws IOException {
        synchronized (lock("owner:" + owner)) {
            Path userDir = userDir(owner);
            if (!Files.isDirectory(userDir)) {
                return 0;
            }
            int count = 0;
            List<Path> files;
            try (Stream<Path> stream = Files.list(userDir)) {
                files = stream.toList();
            }
            for (Path file : files) {
                if (Files.deleteIfExists(file)) {
                    count++;
                }
            }
            Files.deleteIfExists(userDir);
            return count;
        }
    }

    /** Total size of all users' database files. */
    public long usedBytes() throws IOException {
        long total = 0;
        for (long owner : ownersOnDisk()) {
            try (Stream<Path> files = Files.list(userDir(owner))) {
                for (Path file : files.toList()) {
                    try {
                        total += Files.size(file);
                    } catch (java.nio.file.NoSuchFileException e) {
                        // deleted while counting
                    }
                }
            } catch (java.nio.file.NoSuchFileException e) {
                // the directory went away while counting
            }
        }
        return total;
    }

    public long freeBytes() throws IOException {
        return Files.getFileStore(dir).getUsableSpace();
    }

    /** Ids of the users that have a directory on disk. */
    public List<Long> ownersOnDisk() throws IOException {
        Path users = dir.resolve("users");
        if (!Files.isDirectory(users)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(users)) {
            return entries.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> !n.isEmpty() && n.length() <= 18 && n.chars().allMatch(ch -> ch >= '0' && ch <= '9'))
                    .map(Long::parseLong).sorted().toList();
        }
    }

    private void ensureFreeSpace() throws IOException {
        if (minFreeBytes > 0 && Files.getFileStore(dir).getUsableSpace() < minFreeBytes) {
            throw new InsufficientStorageException("the server is low on disk space, try again later");
        }
    }

    private long limitFor(WorkspaceKey key) {
        return key.owner() == null ? maxUploadBytes : Math.min(maxUploadBytes, maxDbBytes);
    }

    private Object lockFor(WorkspaceKey key) {
        return lock(key.owner() == null ? key.asString() : "owner:" + key.owner());
    }

    private Object lock(String name) {
        return locks.computeIfAbsent(name, k -> new Object());
    }

    private Path temp() {
        return dir.resolve(".tmp-" + UUID.randomUUID() + ".part");
    }

    private void copyLimited(InputStream in, Path target, long limit) throws IOException {
        try (OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[1 << 16];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > limit) {
                    throw new IllegalArgumentException("upload is larger than " + limit / MB + " MB");
                }
                out.write(buffer, 0, read);
            }
        }
    }

    private Path gunzipIfNeeded(Path file, long limit) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(2);
        }
        if (head.length < 2 || (head[0] & 0xff) != 0x1f || (head[1] & 0xff) != 0x8b) {
            return file;
        }
        Path plain = temp();
        try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            copyLimited(in, plain, limit);
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
            rejectForeignObjects(c);
            String check = scalar(c, "PRAGMA quick_check");
            if (!"ok".equals(check)) {
                throw new IllegalArgumentException("database failed quick_check: " + check);
            }
        } catch (SQLException e) {
            throw new IllegalArgumentException("not an Indexer database: " + e.getMessage(), e);
        }
    }

    /** A genuine index has tables, indexes and the FTS5 table code_fts only: no views, triggers or other modules. */
    private static void rejectForeignObjects(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT type, name FROM sqlite_master WHERE type NOT IN ('table', 'index')")) {
            if (rs.next()) {
                throw new IllegalArgumentException("not an Indexer database: it contains a " + rs.getString(1)
                        + " '" + rs.getString(2) + "'");
            }
        }
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT name, sql FROM sqlite_master WHERE sql LIKE 'CREATE VIRTUAL TABLE%'")) {
            while (rs.next()) {
                String sql = String.valueOf(rs.getString(2)).toLowerCase(java.util.Locale.ROOT);
                if (!"code_fts".equals(rs.getString(1)) || !sql.contains("using fts5")) {
                    throw new IllegalArgumentException("not an Indexer database: unexpected virtual table '"
                            + rs.getString(1) + "'");
                }
            }
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
