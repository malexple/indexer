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
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

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
        String version = getMeta("schema_version");
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
        });
    }

    public Path path() {
        return path;
    }

    public Connection connection() {
        return connection;
    }

    public void setVacuumOnClose(boolean vacuumOnClose) {
        this.vacuumOnClose = vacuumOnClose;
    }

    public void execute(String sql) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
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

    public String getMeta(String key) throws SQLException {
        return queryString("SELECT value FROM meta WHERE key = ?", key);
    }

    public void putMeta(String key, String value) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO meta(key, value) VALUES (?, ?) "
                        + "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    public Map<String, PriorFile> loadPriorFiles() throws SQLException {
        Map<String, PriorFile> result = new HashMap<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT path, sha256, indexed_at FROM files")) {
            while (rs.next()) {
                result.put(rs.getString(1), new PriorFile(rs.getString(2), rs.getString(3)));
            }
        }
        return result;
    }

    public void wipeAllData() throws SQLException {
        execute("PRAGMA foreign_keys = OFF");
        try {
            inTransaction(() -> {
                for (String table : Schema.WIPE_ORDER) {
                    execute("DELETE FROM " + table);
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