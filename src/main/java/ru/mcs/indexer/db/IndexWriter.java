package ru.mcs.indexer.db;

import ru.mcs.indexer.files.LoadedFile;
import ru.mcs.indexer.files.SkippedFile;
import ru.mcs.indexer.lang.FileFacts;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

public final class IndexWriter implements AutoCloseable {

    private final Db db;
    private final boolean storeContent;
    private final PreparedStatement insertFile;
    private final PreparedStatement insertError;
    private final PreparedStatement lastId;
    private final FactsWriter facts;

    public IndexWriter(Db db, boolean storeContent) throws SQLException {
        this.db = db;
        this.storeContent = storeContent;
        Connection c = db.connection();
        insertFile = c.prepareStatement("INSERT INTO files(path, module, lang, kind, size_bytes, line_count, "
                + "sha256, content, indexed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)");
        insertError = c.prepareStatement("INSERT INTO index_errors(file_id, path, stage, message) VALUES (?, ?, ?, ?)");
        lastId = c.prepareStatement("SELECT last_insert_rowid()");
        facts = new FactsWriter(db);
    }

    public boolean writeFile(LoadedFile file, String indexedAt) throws SQLException {
        return writeFile(file, null, indexedAt);
    }

    /** Writes one file with all its facts in a single transaction; false if a DB error was recorded. */
    public boolean writeFile(LoadedFile file, FileFacts fileFacts, String indexedAt) throws SQLException {
        long[] mark = facts.mark();
        try {
            db.inTransaction(() -> {
                insertFile.setString(1, file.path());
                insertFile.setNull(2, Types.VARCHAR);
                insertFile.setString(3, file.lang());
                insertFile.setString(4, file.kind());
                insertFile.setLong(5, file.sizeBytes());
                insertFile.setInt(6, file.lineCount());
                insertFile.setString(7, file.sha256());
                if (storeContent && file.content() != null) {
                    insertFile.setString(8, file.content());
                } else {
                    insertFile.setNull(8, Types.VARCHAR);
                }
                insertFile.setString(9, indexedAt);
                insertFile.executeUpdate();
                long fileId = lastInsertId();
                if (fileFacts != null) {
                    facts.write(fileId, fileFacts);
                    for (FileFacts.ErrorRow error : fileFacts.errors()) {
                        bindError(fileId, file.path(), error.stage(), error.message());
                    }
                }
                for (String warning : file.warnings()) {
                    bindError(fileId, file.path(), "READ", warning);
                }
            });
            return true;
        } catch (SQLException e) {
            facts.reset(mark);
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
            facts.close();
        } finally {
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
}