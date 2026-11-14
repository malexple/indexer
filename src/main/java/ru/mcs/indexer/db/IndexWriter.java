package ru.mcs.indexer.db;

import ru.mcs.indexer.files.LoadedFile;
import ru.mcs.indexer.files.SkippedFile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

public final class IndexWriter implements AutoCloseable {

    private final Db db;
    private final PreparedStatement insertFile;
    private final PreparedStatement insertError;
    private final PreparedStatement lastId;

    public IndexWriter(Db db) throws SQLException {
        this.db = db;
        Connection connection = db.connection();
        this.insertFile = connection.prepareStatement(
                "INSERT INTO files(path, module, lang, kind, size_bytes, line_count, sha256, content, indexed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)");
        this.insertError = connection.prepareStatement(
                "INSERT INTO index_errors(file_id, path, stage, message) VALUES (?, ?, ?, ?)");
        this.lastId = connection.prepareStatement("SELECT last_insert_rowid()");
    }

    /** Writes one file in its own transaction. Returns false if a DB error was recorded instead. */
    public boolean writeFile(LoadedFile file, String indexedAt) throws SQLException {
        try {
            db.inTransaction(() -> {
                insertFile.setString(1, file.path());
                insertFile.setNull(2, Types.VARCHAR);
                insertFile.setString(3, file.lang());
                insertFile.setString(4, file.kind());
                insertFile.setLong(5, file.sizeBytes());
                insertFile.setInt(6, file.lineCount());
                insertFile.setString(7, file.sha256());
                if (file.content() == null) {
                    insertFile.setNull(8, Types.VARCHAR);
                } else {
                    insertFile.setString(8, file.content());
                }
                insertFile.setString(9, indexedAt);
                insertFile.executeUpdate();
                long fileId = lastInsertId();
                for (String warning : file.warnings()) {
                    bindError(fileId, file.path(), "READ", warning);
                }
            });
            return true;
        } catch (SQLException e) {
            recordError(null, file.path(), "DB", String.valueOf(e.getMessage()));
            return false;
        }
    }

    public void recordError(Long fileId, String path, String stage, String message) throws SQLException {
        db.inTransaction(() -> bindError(fileId, path, stage, message));
    }

    public void recordSkipped(List<SkippedFile> skipped) throws SQLException {
        if (skipped.isEmpty()) {
            return;
        }
        db.inTransaction(() -> {
            for (SkippedFile item : skipped) {
                bindError(null, item.path(), item.stage(), item.message());
            }
        });
    }

    private void bindError(Long fileId, String path, String stage, String message) throws SQLException {
        if (fileId == null) {
            insertError.setNull(1, Types.INTEGER);
        } else {
            insertError.setLong(1, fileId);
        }
        insertError.setString(2, path);
        insertError.setString(3, stage);
        insertError.setString(4, message);
        insertError.executeUpdate();
    }

    private long lastInsertId() throws SQLException {
        try (ResultSet rs = lastId.executeQuery()) {
            if (!rs.next()) {
                throw new SQLException("last_insert_rowid() returned no row");
            }
            return rs.getLong(1);
        }
    }

    @Override
    public void close() throws SQLException {
        try {
            insertFile.close();
        } finally {
            try {
                insertError.close();
            } finally {
                lastId.close();
            }
        }
    }
}