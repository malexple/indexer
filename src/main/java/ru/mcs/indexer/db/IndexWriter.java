package ru.mcs.indexer.db;

import ru.mcs.indexer.files.LoadedFile;
import ru.mcs.indexer.files.SkippedFile;
import ru.mcs.indexer.lang.FileFacts;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Collection;
import java.util.List;

/**
 * Single-threaded writer. In incremental mode it also maintains the external-content FTS index: the old
 * row is sent to FTS 'delete' (with its stored values) BEFORE the files row changes, the new values are
 * added after the insert, all in the same transaction.
 */
public final class IndexWriter implements AutoCloseable {

    private final Db db;
    private final boolean storeContent;
    private final boolean incremental;
    private final PreparedStatement insertFile;
    private final PreparedStatement insertError;
    private final PreparedStatement lastId;
    private final PreparedStatement selectOld;
    private final PreparedStatement deleteFile;
    private final PreparedStatement ftsDelete;
    private final PreparedStatement ftsInsert;
    private final FactsWriter facts;
    private final ExtraWriter extra;
    private final GradleRegistry gradle = new GradleRegistry();

    public IndexWriter(Db db, boolean storeContent) throws SQLException {
        this(db, storeContent, false);
    }

    public IndexWriter(Db db, boolean storeContent, boolean incremental) throws SQLException {
        this.db = db;
        this.storeContent = storeContent;
        this.incremental = incremental;
        Connection c = db.connection();
        insertFile = c.prepareStatement("INSERT INTO files(path, module, lang, kind, size_bytes, line_count, "
                + "sha256, content, indexed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)");
        insertError = c.prepareStatement("INSERT INTO index_errors(file_id, path, stage, message) VALUES (?, ?, ?, ?)");
        lastId = c.prepareStatement("SELECT last_insert_rowid()");
        selectOld = c.prepareStatement("SELECT id, content FROM files WHERE path = ?");
        deleteFile = c.prepareStatement("DELETE FROM files WHERE id = ?");
        ftsDelete = c.prepareStatement(
                "INSERT INTO code_fts(code_fts, rowid, path, content) VALUES ('delete', ?, ?, ?)");
        ftsInsert = c.prepareStatement("INSERT INTO code_fts(rowid, path, content) VALUES (?, ?, ?)");
        facts = new FactsWriter(db);
        extra = new ExtraWriter(db);
    }

    public boolean writeFile(LoadedFile file, String indexedAt) throws SQLException {
        return writeFile(file, null, indexedAt);
    }

    /** Writes one file with all its facts in a single transaction; false if a DB error was recorded. */
    public boolean writeFile(LoadedFile file, FileFacts fileFacts, String indexedAt) throws SQLException {
        long[] mark = facts.mark();
        try {
            db.inTransaction(() -> {
                if (incremental) {
                    removeExisting(file.path());
                }
                String stored = storeContent ? file.content() : null;
                insertFile.setString(1, file.path());
                insertFile.setNull(2, Types.VARCHAR);
                insertFile.setString(3, file.lang());
                insertFile.setString(4, file.kind());
                insertFile.setLong(5, file.sizeBytes());
                insertFile.setInt(6, file.lineCount());
                insertFile.setString(7, file.sha256());
                setNullable(insertFile, 8, stored);
                insertFile.setString(9, indexedAt);
                insertFile.executeUpdate();
                long fileId = lastInsertId();
                if (incremental) {
                    ftsInsert.setLong(1, fileId);
                    ftsInsert.setString(2, file.path());
                    setNullable(ftsInsert, 3, stored);
                    ftsInsert.executeUpdate();
                }
                if (fileFacts != null) {
                    facts.write(fileId, fileFacts);
                    extra.write(fileId, fileFacts);
                    for (FileFacts.ErrorRow error : fileFacts.errors()) {
                        bindError(fileId, file.path(), error.stage(), error.message());
                    }
                }
                for (String warning : file.warnings()) {
                    bindError(fileId, file.path(), "READ", warning);
                }
            });
            gradle.collect(file.path(), fileFacts);
            return true;
        } catch (SQLException e) {
            facts.reset(mark);
            recordError(null, file.path(), "DB", String.valueOf(e.getMessage()));
            return false;
        }
    }

    /** Removes files (FTS entry first, then the row with all its cascaded data). Returns how many existed. */
    public int removeFiles(Collection<String> paths) throws SQLException {
        int[] removed = {0};
        db.inTransaction(() -> {
            for (String path : paths) {
                if (removeExisting(path)) {
                    removed[0]++;
                }
            }
        });
        return removed[0];
    }

    /** Feeds an unchanged Gradle file (not rewritten in the database) into the module computation. */
    public void collectGradle(String path, FileFacts fileFacts) {
        gradle.collect(path, fileFacts);
    }

    /** Call once after all files are written: gradle_modules, gradle_deps and files.module. */
    public void finishGradle(String defaultRootName) throws SQLException {
        gradle.finish(db, defaultRootName);
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

    /** Must run inside a transaction. FTS 'delete' needs the exact values that were indexed. */
    private boolean removeExisting(String path) throws SQLException {
        long id;
        String content;
        selectOld.setString(1, path);
        try (ResultSet rs = selectOld.executeQuery()) {
            if (!rs.next()) {
                return false;
            }
            id = rs.getLong(1);
            content = rs.getString(2);
        }
        ftsDelete.setLong(1, id);
        ftsDelete.setString(2, path);
        setNullable(ftsDelete, 3, content);
        ftsDelete.executeUpdate();
        deleteFile.setLong(1, id);
        deleteFile.executeUpdate();
        return true;
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

    private static void setNullable(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
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
        SQLException failure = null;
        for (AutoCloseable closeable : new AutoCloseable[]{extra, facts, insertFile, insertError, lastId,
                selectOld, deleteFile, ftsDelete, ftsInsert}) {
            try {
                closeable.close();
            } catch (Exception e) {
                if (failure == null) {
                    failure = e instanceof SQLException sql ? sql : new SQLException(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}