package ru.mcs.indexer.db;

import ru.mcs.indexer.lang.FileFacts;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/** Inserts the AST rows of one file. Must be called inside a transaction, from the single writer thread. */
final class FactsWriter implements AutoCloseable {

    private final PreparedStatement insertType;
    private final PreparedStatement insertMember;
    private final PreparedStatement insertRelation;
    private final PreparedStatement insertAnnotation;
    private final PreparedStatement insertImport;
    private final PreparedStatement insertCall;
    private final PreparedStatement insertDep;
    private long nextTypeId;
    private long nextMemberId;

    FactsWriter(Db db) throws SQLException {
        Connection c = db.connection();
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
        insertCall = c.prepareStatement("INSERT INTO calls(file_id, caller_member_id, call_kind, callee_name, "
                + "callee_owner_fqn, callee_signature, resolved, line) VALUES (?,?,?,?,?,?,?,?)");
        insertDep = c.prepareStatement("INSERT INTO type_deps(from_type_id, to_fqn, dep_kind, resolved) "
                + "VALUES (?,?,?,?)");
        nextTypeId = db.queryLong("SELECT COALESCE(MAX(id), 0) + 1 FROM types");
        nextMemberId = db.queryLong("SELECT COALESCE(MAX(id), 0) + 1 FROM members");
    }

    long[] mark() {
        return new long[]{nextTypeId, nextMemberId};
    }

    void reset(long[] mark) {
        nextTypeId = mark[0];
        nextMemberId = mark[1];
    }

    void write(long fileId, FileFacts facts) throws SQLException {
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

        for (FileFacts.CallRow call : facts.calls()) {
            insertCall.setLong(1, fileId);
            if (call.callerLocal() < 0) {
                insertCall.setNull(2, Types.INTEGER);
            } else {
                insertCall.setLong(2, memberIds[call.callerLocal()]);
            }
            insertCall.setString(3, call.callKind());
            insertCall.setString(4, call.calleeName());
            setNullable(insertCall, 5, call.calleeOwnerFqn());
            setNullable(insertCall, 6, call.calleeSignature());
            insertCall.setInt(7, call.resolved() ? 1 : 0);
            insertCall.setInt(8, call.line());
            insertCall.addBatch();
        }
        insertCall.executeBatch();

        for (FileFacts.DepRow dep : facts.deps()) {
            insertDep.setLong(1, typeIds[dep.typeLocal()]);
            insertDep.setString(2, dep.toFqn());
            insertDep.setString(3, dep.depKind());
            insertDep.setInt(4, dep.resolved() ? 1 : 0);
            insertDep.addBatch();
        }
        insertDep.executeBatch();
    }

    private static void setNullable(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    @Override
    public void close() throws SQLException {
        for (PreparedStatement ps : new PreparedStatement[]{insertType, insertMember, insertRelation,
                insertAnnotation, insertImport, insertCall, insertDep}) {
            ps.close();
        }
    }
}