package ru.mcs.mcpsql.users;

import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** The small SQLite file with users, key pairs and daily counters. Times are epoch seconds. */
public final class UserDb {

    private static final List<String> SCHEMA = List.of(
            "CREATE TABLE IF NOT EXISTS users ("
                    + "id INTEGER PRIMARY KEY, "
                    + "tg_id INTEGER NOT NULL UNIQUE, "
                    + "created_at INTEGER NOT NULL, "
                    + "banned INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS key_pairs ("
                    + "id INTEGER PRIMARY KEY, "
                    + "user_id INTEGER NOT NULL REFERENCES users(id), "
                    + "ttl_kind TEXT NOT NULL CHECK (ttl_kind IN ('5h','1d','7d')), "
                    + "read_hash BLOB NOT NULL UNIQUE, "
                    + "write_hash BLOB NOT NULL UNIQUE, "
                    + "stats_hash BLOB NOT NULL UNIQUE, "
                    + "created_at INTEGER NOT NULL, "
                    + "expires_at INTEGER NOT NULL, "
                    + "revoked_at INTEGER)",
            "CREATE INDEX IF NOT EXISTS key_pairs_user ON key_pairs(user_id, expires_at)",
            "CREATE TABLE IF NOT EXISTS usage_daily ("
                    + "user_id INTEGER NOT NULL REFERENCES users(id), "
                    + "day TEXT NOT NULL, "
                    + "uploads INTEGER NOT NULL DEFAULT 0, "
                    + "upload_bytes INTEGER NOT NULL DEFAULT 0, "
                    + "patches INTEGER NOT NULL DEFAULT 0, "
                    + "requests INTEGER NOT NULL DEFAULT 0, "
                    + "response_bytes INTEGER NOT NULL DEFAULT 0, "
                    + "key_issues INTEGER NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY (user_id, day))",
            "CREATE TABLE IF NOT EXISTS user_state ("
                    + "user_id INTEGER PRIMARY KEY REFERENCES users(id), "
                    + "last_patch_at INTEGER NOT NULL DEFAULT 0)");

    private final String url;

    public UserDb(Path file) throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        this.url = "jdbc:sqlite:" + absolute;
        try (Connection c = open(); Statement s = c.createStatement()) {
            for (String ddl : SCHEMA) {
                s.execute(ddl);
            }
            s.execute("PRAGMA user_version = 2");
        } catch (SQLException e) {
            throw new IOException("cannot open " + absolute + ": " + e.getMessage(), e);
        }
    }

    public Connection open() throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.setBusyTimeout(5000);
        config.enforceForeignKeys(true);
        return config.createConnection(url);
    }
}
