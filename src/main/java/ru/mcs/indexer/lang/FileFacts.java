package ru.mcs.indexer.lang;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Rows extracted from one file. Cross references use local indexes into these lists. */
public final class FileFacts {

    public record ImportRow(String name, boolean isStatic, boolean isAsterisk, int line) {
    }

    public record TypeRow(int parentLocal, String fqn, String simpleName, String packageName,
                          String kind, String modifiers, boolean isPublic,
                          int lineFrom, int lineTo, String docSummary) {
    }

    public record RelationRow(int typeLocal, String relation, String targetName,
                              String targetFqn, boolean resolved) {
    }

    public record MemberRow(int typeLocal, String kind, String name, String signature,
                            String returnType, String modifiers,
                            int lineFrom, int lineTo, String docSummary) {
    }

    public record AnnotationRow(String ownerKind, int ownerLocal, String name, String args, int line) {
    }

    /** callerLocal is a local member index, or -1 when the call is outside any member. */
    public record CallRow(int callerLocal, String callKind, String calleeName, String calleeOwnerFqn,
                          String calleeSignature, boolean resolved, int line) {
    }

    public record DepRow(int typeLocal, String toFqn, String depKind, boolean resolved) {
    }

    public record ErrorRow(String stage, String message) {
    }

    private final List<ImportRow> imports = new ArrayList<>();
    private final List<TypeRow> types = new ArrayList<>();
    private final List<RelationRow> relations = new ArrayList<>();
    private final List<MemberRow> members = new ArrayList<>();
    private final List<AnnotationRow> annotations = new ArrayList<>();
    private final List<CallRow> calls = new ArrayList<>();
    private final Map<String, DepRow> deps = new LinkedHashMap<>();
    private final List<ErrorRow> errors = new ArrayList<>();

    public void addImport(ImportRow row) {
        imports.add(row);
    }

    public int addType(TypeRow row) {
        types.add(row);
        return types.size() - 1;
    }

    public void addRelation(RelationRow row) {
        relations.add(row);
    }

    public int addMember(MemberRow row) {
        members.add(row);
        return members.size() - 1;
    }

    public void addAnnotation(AnnotationRow row) {
        annotations.add(row);
    }

    public void addCall(CallRow row) {
        calls.add(row);
    }

    /** Deduplicates by (type, kind, target); a resolved duplicate upgrades an unresolved one. */
    public void addDep(int typeLocal, String toFqn, String depKind, boolean resolved) {
        String key = typeLocal + "|" + depKind + "|" + toFqn;
        DepRow existing = deps.get(key);
        if (existing == null || (!existing.resolved() && resolved)) {
            deps.put(key, new DepRow(typeLocal, toFqn, depKind, resolved));
        }
    }

    public void addError(String stage, String message) {
        errors.add(new ErrorRow(stage, message));
    }

    public List<ImportRow> imports() {
        return imports;
    }

    public List<TypeRow> types() {
        return types;
    }

    public List<RelationRow> relations() {
        return relations;
    }

    public List<MemberRow> members() {
        return members;
    }

    public List<AnnotationRow> annotations() {
        return annotations;
    }

    public List<CallRow> calls() {
        return calls;
    }

    public List<DepRow> deps() {
        return new ArrayList<>(deps.values());
    }

    public List<ErrorRow> errors() {
        return errors;
    }
}