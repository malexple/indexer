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
    private final PreparedStatement insertType;
    private final PreparedStatement insertMember;
    private final PreparedStatement insertRelation;
    private final PreparedStatement insertAnnotation;
    private final PreparedStatement insertImport;
    private long nextTypeId;
    private long nextMemberId;

    public IndexWriter(Db db, boolean storeContent) throws SQLException {
        this.db = db;
        this.storeContent = storeContent;
        Connection c = db.connection();
        insertFile = c.prepareStatement("INSERT INTO files(path, module, lang, kind, size_bytes, line_count, "
                + "sha256, content, indexed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)");
        insertError = c.prepareStatement("INSERT INTO index_errors(file_id, path, stage, message) VALUES (?, ?, ?, ?)");
        lastId = c.prepareStatement("SELECT last_insert_rowid()");
        insertType = c.prepareStatement("INSERT INTO types(id, file_id, parent_type_id, fqn, simple_name, package, "
                + "kind, modifiers, is_public, line_from, line_to, doc_summary) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)");
        insertMember = c.prepareStatement("INSERT INTO members(id, type_id, file_id, kind, name, signature, "
                + "return_type, modifiers, line_from, line_to, doc_summary) VALUES (?,?,?,?,?,?,?,?,?,?,?)");
        insertRelation = c.prepareStatement("INSERT INTO type_relations(type_id, relation, target_name, "
                + "target_fqn, resolved) VALUES (?,?,?,?,?)");
        insertAnnotation = c.prepareStatement("INSERT INTO annotations(file_id, owner_kind, owner_id, name, args, "
                + "line) VALUES (?,?,?,?,?,?)");
        insertImport = c.prepareStatement("INSERT INTO imports(file_id, name, is_static, is_asterisk, line) "
                + "VALUES (?,?,?,?,?)");
        nextTypeId = db.queryLong("SELECT COALESCE(MAX(id), 0) + 1 FROM types");
        nextMemberId = db.queryLong("SELECT COALESCE(MAX(id), 0) + 1 FROM members");
    }

    public boolean writeFile(LoadedFile file, String indexedAt) throws SQLException {
        return writeFile(file, null, indexedAt);
    }

    /** Writes one file with all its facts in a single transaction; false if a DB error was recorded. */
    public boolean writeFile(LoadedFile file, FileFacts facts, String indexedAt) throws SQLException {
        long typeMark = nextTypeId;
        long memberMark = nextMemberId;
        try {
            db.inTransaction(() -> {
                insertFile.setString(1, file.path());
                insertFile.setNull(2, Types.VARCHAR);
                insertFile.setString(3, file.lang());
                insertFile.setString(4, file.kind());
                insertFile.setLong(5, file.sizeBytes());
                insertFile.setInt(6, file.lineCount());
                insertFile.setString(7, file.sha256());
                setNullable(insertFile, 8, storeContent ? file.content() : null);
                insertFile.setString(9, indexedAt);
                insertFile.executeUpdate();
                long fileId = lastInsertId();
                if (facts != null) {
                    writeFacts(fileId, facts);
                    for (FileFacts.ErrorRow error : facts.errors()) {
                        bindError(fileId, file.path(), error.stage(), error.message());
                    }
                }
                for (String warning : file.warnings()) {
                    bindError(fileId, file.path(), "READ", warning);
                }
            });
            return true;
        } catch (SQLException e) {
            nextTypeId = typeMark;
            nextMemberId = memberMark;
            recordError(null, file.path(), "DB", String.valueOf(e.getMessage()));
            return false;
        }
    }

    private void writeFacts(long fileId, FileFacts facts) throws SQLException {
        long[] typeIds = new long[facts.types().size()];
        for (int i = 0; i < typeIds.length; i++) {
            FileFacts.TypeRow t = facts.types().get(i);
            long id = nextTypeId++;
            typeIds[i] = id;
            insertType.setLong(1, id);
            insertType.setLong(2, fileId);
            if (t.parentLocal() < 0) {
                insertType.setNull(3, Types.INTEGER);
            } else {
                insertType.setLong(3, typeIds[t.parentLocal()]);
            }
            insertType.setString(4, t.fqn());
            insertType.setString(5, t.simpleName());
            insertType.setString(6, t.packageName());
            insertType.setString(7, t.kind());
            insertType.setString(8, t.modifiers());
            insertType.setInt(9, t.isPublic() ? 1 : 0);
            insertType.setInt(10, t.lineFrom());
            insertType.setInt(11, t.lineTo());
            setNullable(insertType, 12, t.docSummary());
            insertType.addBatch();
        }
        insertType.executeBatch();

        long[] memberIds = new long[facts.members().size()];
        for (int i = 0; i < memberIds.length; i++) {
            FileFacts.MemberRow m = facts.members().get(i);
            long id = nextMemberId++;
            memberIds[i] = id;
            insertMember.setLong(1, id);
            insertMember.setLong(2, typeIds[m.typeLocal()]);
            insertMember.setLong(3, fileId);
            insertMember.setString(4, m.kind());
            insertMember.setString(5, m.name());
            insertMember.setString(6, m.signature());
            setNullable(insertMember, 7, m.returnType());
            insertMember.setString(8, m.modifiers());
            insertMember.setInt(9, m.lineFrom());
            insertMember.setInt(10, m.lineTo());
            setNullable(insertMember, 11, m.docSummary());
            insertMember.addBatch();
        }
        insertMember.executeBatch();

        for (FileFacts.RelationRow r : facts.relations()) {
            insertRelation.setLong(1, typeIds[r.typeLocal()]);
            insertRelation.setString(2, r.relation());
            insertRelation.setString(3, r.targetName());
            setNullable(insertRelation, 4, r.targetFqn());
            insertRelation.setInt(5, r.resolved() ? 1 : 0);
            insertRelation.addBatch();
        }
        insertRelation.executeBatch();

        for (FileFacts.AnnotationRow a : facts.annotations()) {
            long ownerId = a.ownerKind().equals("TYPE") ? typeIds[a.ownerLocal()] : memberIds[a.ownerLocal()];
            insertAnnotation.setLong(1, fileId);
            insertAnnotation.setString(2, a.ownerKind());
            insertAnnotation.setLong(3, ownerId);
            insertAnnotation.setString(4, a.name());
            setNullable(insertAnnotation, 5, a.args());
            insertAnnotation.setInt(6, a.line());
            insertAnnotation.addBatch();
        }
        insertAnnotation.executeBatch();

        for (FileFacts.ImportRow i : facts.imports()) {
            insertImport.setLong(1, fileId);
            insertImport.setString(2, i.name());
            insertImport.setInt(3, i.isStatic() ? 1 : 0);
            insertImport.setInt(4, i.isAsterisk() ? 1 : 0);
            insertImport.setInt(5, i.line());
            insertImport.addBatch();
        }
        insertImport.executeBatch();
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
        for (PreparedStatement ps : new PreparedStatement[]{insertFile, insertError, lastId, insertType,
                insertMember, insertRelation, insertAnnotation, insertImport}) {
            ps.close();
        }
    }
}