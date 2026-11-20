package ru.mcs.indexer.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class Db implements AutoCloseable {

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @FunctionalInterface
    public interface SqlWork {
        void run() throws SQLException;
    }

    private static final byte[] SQLITE_MAGIC = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);

    private final Path path;
    private final Connection connection;
    private boolean vacuumOnClose;
    private String project;

    private Db(Path path, Connection connection) {
        this.path = path;
        this.connection = connection;
    }

    public static Db open(Path path) throws SQLException, IOException {
        Path abs = path.toAbsolutePath().normalize();
        Path parent = abs.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        checkMagic(abs);
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + abs);
        Db db = new Db(abs, connection);
        try {
            db.execute("PRAGMA foreign_keys = ON");
            db.execute("PRAGMA journal_mode = WAL");
            db.execute("PRAGMA synchronous = NORMAL");
            db.checkCompatibility();
            db.createSchema();
        } catch (SQLException | RuntimeException e) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // original failure is more important
            }
            throw e;
        }
        return db;
    }

    private static void checkMagic(Path file) throws IOException {
        if (!Files.exists(file) || Files.size(file) == 0) {
            return;
        }
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(SQLITE_MAGIC.length);
        }
        if (!Arrays.equals(head, SQLITE_MAGIC)) {
            throw new IllegalStateException("not an SQLite database, refusing to overwrite: " + file);
        }
    }

    private void checkCompatibility() throws SQLException {
        long tables = queryLong("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table'");
        if (tables == 0) {
            return;
        }
        long metaTables = queryLong(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'meta'");
        if (metaTables == 0) {
            throw new IllegalStateException(
                    "database has tables but no 'meta' table, not an Indexer database: " + path);
        }
        String version = queryString("SELECT value FROM meta WHERE key = 'schema_version' LIMIT 1");
        if (!String.valueOf(Schema.VERSION).equals(version)) {
            throw new IllegalStateException("unsupported schema_version '" + version + "' (expected "
                    + Schema.VERSION + "); delete the file and run again: " + path);
        }
    }

    private void createSchema() throws SQLException {
        inTransaction(() -> {
            for (String statement : Schema.STATEMENTS) {
                execute(statement);
            }
            update("INSERT OR IGNORE INTO meta(scope, key, value) VALUES ('', 'db_id', ?)",
                    UUID.randomUUID().toString());
            update("INSERT OR IGNORE INTO meta(scope, key, value) VALUES ('', 'seq', '0')");
        });
    }

    public Path path() {
        return path;
    }

    public Connection connection() {
        return connection;
    }

    /** Slug of the project this run works on; set by {@link #useProject(String)}. */
    public String project() {
        if (project == null) {
            throw new IllegalStateException("no project selected, call useProject first");
        }
        return project;
    }

    /** Registers the project (if new) and makes it the scope of getMeta/putMeta, loadPriorFiles and wipeProject. */
    public void useProject(String slug) throws SQLException {
        if (!ProjectNames.isValid(slug)) {
            throw new IllegalArgumentException("invalid project name: " + slug);
        }
        update("INSERT OR IGNORE INTO projects(slug, created_at) VALUES (?, ?)", slug,
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        this.project = slug;
    }

    public void setVacuumOnClose(boolean vacuumOnClose) {
        this.vacuumOnClose = vacuumOnClose;
    }

    public void execute(String sql) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        }
    }

    public int update(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    public long queryLong(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    public String queryString(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    /** Rows changed by this connection since it was opened; a before/after difference detects real changes. */
    public long totalChanges() throws SQLException {
        return queryLong("SELECT total_changes()");
    }

    public void inTransaction(SqlWork work) throws SQLException {
        boolean previous = connection.getAutoCommit();
        if (!previous) {
            work.run();
            return;
        }
        connection.setAutoCommit(false);
        try {
            work.run();
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /** Project-scoped meta value. */
    public String getMeta(String key) throws SQLException {
        return queryString("SELECT value FROM meta WHERE scope = ? AND key = ?", project(), key);
    }

    public void putMeta(String key, String value) throws SQLException {
        upsertMeta(project(), key, value);
    }

    /** Workspace-wide meta value (scope ''): schema_version, db_id, seq. */
    public String getGlobalMeta(String key) throws SQLException {
        return queryString("SELECT value FROM meta WHERE scope = '' AND key = ?", key);
    }

    /** Increments the workspace change counter that patches are chained on. */
    public void bumpSeq() throws SQLException {
        update("UPDATE meta SET value = CAST(CAST(value AS INTEGER) + 1 AS TEXT) "
                + "WHERE scope = '' AND key = 'seq'");
    }

    private void upsertMeta(String scope, String key, String value) throws SQLException {
        update("INSERT INTO meta(scope, key, value) VALUES (?, ?, ?) "
                + "ON CONFLICT(scope, key) DO UPDATE SET value = excluded.value", scope, key, value);
    }

    public Map<String, PriorFile> loadPriorFiles() throws SQLException {
        Map<String, PriorFile> result = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT path, sha256, indexed_at FROM files WHERE project_id = ?")) {
            ps.setString(1, project());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString(1), new PriorFile(rs.getString(2), rs.getString(3)));
                }
            }
        }
        return result;
    }

    /** Removes every row of the current project (and its meta) without touching other projects. */
    public void wipeProject() throws SQLException {
        String slug = project();
        execute("PRAGMA foreign_keys = OFF");
        try {
            inTransaction(() -> {
                for (String sql : Schema.WIPE_PROJECT) {
                    update(sql, slug);
                }
            });
        } finally {
            execute("PRAGMA foreign_keys = ON");
        }
    }

    public void rebuildFts() throws SQLException {
        inTransaction(() -> execute("INSERT INTO code_fts(code_fts) VALUES ('rebuild')"));
    }

    @Override
    public void close() throws SQLException {
        if (connection.isClosed()) {
            return;
        }
        try {
            if (!connection.getAutoCommit()) {
                connection.rollback();
                connection.setAutoCommit(true);
            }
            execute("PRAGMA wal_checkpoint(TRUNCATE)");
            execute("PRAGMA journal_mode = DELETE");
            execute("PRAGMA optimize");
            if (vacuumOnClose) {
                execute("VACUUM");
            }
        } finally {
            connection.close();
        }
    }
}