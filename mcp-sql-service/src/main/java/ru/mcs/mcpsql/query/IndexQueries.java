package ru.mcs.mcpsql.query;

import org.springframework.stereotype.Service;
import ru.mcs.mcpsql.config.ServiceProperties;
import ru.mcs.mcpsql.workspace.WorkspaceService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** All read-only questions the MCP tools can ask of an index; every method returns a compact text answer. */
@Service
public class IndexQueries {

    private static final int MAX_LINES = 400;
    private static final int LIST_LIMIT = 15;

    private static final String SCHEMA_HINT = """
            tables: projects(slug) files(id,project_id,path,module,lang,kind,size_bytes,line_count,sha256,content,indexed_at) \
            types(id,file_id,parent_type_id,fqn,simple_name,package,kind,modifiers,is_public,line_from,line_to,doc_summary) \
            type_relations(type_id,relation,target_name,target_fqn,resolved) \
            members(id,type_id,file_id,kind,name,signature,return_type,modifiers,line_from,line_to,doc_summary) \
            calls(file_id,caller_member_id,call_kind,callee_name,callee_owner_fqn,callee_signature,resolved,line) \
            type_deps(from_type_id,to_fqn,dep_kind,resolved) annotations(file_id,owner_kind,owner_id,name,args,line) \
            imports(file_id,name,is_static,is_asterisk,line) gradle_modules(id,project_id,name,dir,build_file) \
            gradle_deps(module_id,config,dep_kind,target,line) java_modules(id,file_id,name,is_open) \
            module_directives(java_module_id,directive,target,extra,line) config_entries(file_id,key_path,value,line) \
            index_errors(project_id,file_id,path,stage,message) code_fts(path,content) meta(scope,key,value)
            notes: types, members, calls, type_deps reach their project through files.project_id; line numbers refer to \
            files.content with \\n endings; code_fts text must be wrapped in double quotes; resolved=0 means a best-effort name.""";

    private static final Pattern JDK_PREFIX = Pattern.compile("\\bjava\\.(?:lang|util|io|sql|time|nio\\.file)\\.(?=[A-Z])");

    private record TypeRow(long id, String project, String kind, String modifiers, String path, int from, int to,
                           String doc, String simpleName) {
    }

    @FunctionalInterface
    private interface Work {
        void run(String workspace, Connection c, Budget b) throws SQLException;
    }

    private final WorkspaceService workspaces;
    private final ServiceProperties properties;

    public IndexQueries(WorkspaceService workspaces, ServiceProperties properties) {
        this.workspaces = workspaces;
        this.properties = properties;
    }

    private String run(String workspace, Work work) {
        String resolved = workspaces.resolve(workspace);
        Budget budget = new Budget(properties.maxResponseChars());
        try (Connection c = workspaces.openReadOnly(resolved)) {
            work.run(resolved, c, budget);
        } catch (SQLException e) {
            throw new IllegalStateException("query failed: " + e.getMessage(), e);
        }
        return budget.finish();
    }

    public String indexInfo(String workspace, boolean includeSchema) {
        return run(workspace, (ws, c, b) -> {
            Map<String, String> global = meta(c, "");
            b.line("workspace: " + ws + " | db_id: " + global.get("db_id") + " | seq: " + global.get("seq")
                    + " | schema: " + global.get("schema_version"));
            b.line("project\tcommit\tbranch\tdirty\tindexed_at\tfiles\ttypes\tmembers\terrors\tsource\tresolve");
            List<String> slugs = new ArrayList<>();
            Jdbc.query(c, "SELECT slug FROM projects ORDER BY slug", rs -> slugs.add(rs.getString(1)));
            for (String slug : slugs) {
                Map<String, String> m = meta(c, slug);
                b.line(String.join("\t", slug, nz(m.get("git_commit_short")), nz(m.get("git_branch")),
                        nz(m.get("git_dirty")), nz(m.get("indexed_at")), nz(m.get("file_count")),
                        nz(m.get("type_count")), nz(m.get("member_count")), nz(m.get("error_count")),
                        nz(m.get("source_included")), nz(m.get("symbol_resolution"))));
            }
            for (String slug : slugs) {
                String notes = meta(c, slug).get("notes");
                if (notes != null && !notes.isBlank()) {
                    b.line("notes[" + slug + "]: " + notes);
                }
            }
            if (includeSchema) {
                b.line(SCHEMA_HINT);
            }
        });
    }

    public String findType(String name, String project, String workspace) {
        return run(workspace, (ws, c, b) -> {
            String like = "%." + escapeLike(name);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT f.project_id AS project, t.fqn, t.kind, f.path, t.line_from AS from_line, t.line_to AS to_line "
                            + "FROM types t JOIN files f ON f.id = t.file_id "
                            + "WHERE (t.simple_name = ? OR t.fqn = ? OR t.fqn LIKE ? ESCAPE '\\') "
                            + "AND (? IS NULL OR f.project_id = ?) ORDER BY f.project_id, t.fqn LIMIT 30")) {
                Jdbc.bind(ps, new Object[]{name, name, like, project, project});
                try (ResultSet rs = ps.executeQuery()) {
                    int rows = Jdbc.table(rs, b, 30);
                    if (rows == 0) {
                        b.line("no type matches '" + name + "'; try a shorter name or search");
                    }
                }
            }
        });
    }

    public String context(String fqn, String project, String workspace) {
        return run(workspace, (ws, c, b) -> {
            List<TypeRow> types = findExact(c, fqn, project);
            if (types.isEmpty()) {
                b.line("no type with fqn '" + fqn + "'; use find_type first");
                return;
            }
            if (types.size() > 1) {
                b.line("the fqn exists in several projects, pass 'project': "
                        + types.stream().map(TypeRow::project).toList());
                return;
            }
            TypeRow t = types.get(0);
            String pkg = packageOf(fqn);
            b.line("type: " + fqn + " [" + t.kind() + "] " + t.project() + " " + t.path() + ":" + t.from() + "-" + t.to()
                    + (t.modifiers().isEmpty() ? "" : " " + t.modifiers()));
            if (t.doc() != null && !t.doc().isBlank()) {
                b.line("doc: " + Jdbc.cell(t.doc()));
            }
            inline(b, "supertypes:", c, "SELECT relation, COALESCE(target_fqn, target_name) FROM type_relations "
                            + "WHERE type_id = ? ORDER BY relation, 2",
                    rs -> rs.getString(1) + " " + shorten(rs.getString(2), pkg), t.id());
            inline(b, "subtypes:", c, "SELECT t2.fqn, r.relation FROM type_relations r JOIN types t2 ON t2.id = r.type_id "
                    + "WHERE r.target_fqn = ? OR (r.target_fqn IS NULL AND r.target_name = ?) ORDER BY t2.fqn LIMIT "
                    + LIST_LIMIT, rs -> shorten(rs.getString(1), pkg) + " (" + rs.getString(2) + ")", fqn, t.simpleName());

            List<String> memberLines = new ArrayList<>();
            List<String> privateFields = new ArrayList<>();
            Jdbc.query(c, "SELECT kind, modifiers, signature, line_from, line_to FROM members "
                    + "WHERE type_id = ? ORDER BY line_from", rs -> {
                String kind = rs.getString(1);
                String modifiers = rs.getString(2);
                String signature = rs.getString(3);
                if (kind.equals("FIELD") && modifiers.contains("private")) {
                    int colon = signature.indexOf(':');
                    privateFields.add(colon > 0 ? signature.substring(0, colon) : signature);
                } else {
                    memberLines.add(kind + " " + modifiers + " " + shorten(signature, pkg) + " "
                            + rs.getInt(4) + "-" + rs.getInt(5));
                }
                return true;
            }, t.id());
            if (!memberLines.isEmpty()) {
                b.line("members (kind modifiers signature lines):");
                for (String line : memberLines) {
                    if (!b.line(line)) {
                        return;
                    }
                }
            }
            if (!privateFields.isEmpty()) {
                b.line("private fields: " + String.join(", ", privateFields));
            }

            inline(b, "depends on (non-JDK):", c, "SELECT DISTINCT to_fqn FROM type_deps WHERE from_type_id = ? "
                            + "AND to_fqn NOT LIKE 'java.%' AND to_fqn NOT LIKE 'javax.%' AND to_fqn <> ? ORDER BY to_fqn",
                    rs -> shorten(rs.getString(1), pkg), t.id(), fqn);
            inline(b, "used by:", c, "SELECT DISTINCT t2.fqn FROM type_deps d JOIN types t2 ON t2.id = d.from_type_id "
                            + "WHERE d.to_fqn = ? AND t2.id <> ? ORDER BY t2.fqn LIMIT " + LIST_LIMIT,
                    rs -> shorten(rs.getString(1), pkg), fqn, t.id());
            inline(b, "resolved callers (class xN):", c, "SELECT ct.fqn, COUNT(*) FROM calls cl "
                    + "JOIN members cm ON cm.id = cl.caller_member_id JOIN types ct ON ct.id = cm.type_id "
                    + "WHERE cl.callee_owner_fqn = ? AND ct.fqn <> ? GROUP BY ct.fqn ORDER BY 2 DESC, 1 LIMIT "
                    + LIST_LIMIT, rs -> shorten(rs.getString(1), pkg) + " x" + rs.getInt(2), fqn, fqn);
        });
    }

    public String readSource(String fqn, String member, String project, String workspace) {
        return run(workspace, (ws, c, b) -> {
            boolean wholeType = member == null || member.isBlank();
            String sql = wholeType
                    ? "SELECT f.project_id, f.path, f.content, t.line_from, t.line_to, t.fqn FROM types t "
                    + "JOIN files f ON f.id = t.file_id WHERE t.fqn = ? AND (? IS NULL OR f.project_id = ?) "
                    + "ORDER BY f.project_id LIMIT 3"
                    : "SELECT f.project_id, f.path, f.content, m.line_from, m.line_to, m.signature FROM members m "
                    + "JOIN types t ON t.id = m.type_id JOIN files f ON f.id = m.file_id "
                    + "WHERE t.fqn = ? AND m.signature LIKE ? ESCAPE '\\' AND (? IS NULL OR f.project_id = ?) "
                    + "ORDER BY f.project_id, m.line_from LIMIT 4";
            Object[] params = wholeType
                    ? new Object[]{fqn, project, project}
                    : new Object[]{fqn, escapeLike(member.strip()) + "%", project, project};
            int[] found = {0};
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Jdbc.bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        found[0]++;
                        printSource(b, rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5),
                                rs.getString(6));
                    }
                }
            }
            if (found[0] == 0) {
                b.line("nothing found for " + fqn + (wholeType ? "" : " member '" + member + "'")
                        + "; check names with find_type / context");
            }
        });
    }

    public String readLines(String path, int from, int to, String project, String workspace) {
        return run(workspace, (ws, c, b) -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT f.project_id, f.path, f.content FROM files f "
                    + "WHERE f.path = ? AND (? IS NULL OR f.project_id = ?) ORDER BY f.project_id LIMIT 3")) {
                Jdbc.bind(ps, new Object[]{path, project, project});
                int found = 0;
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        found++;
                        printSource(b, rs.getString(1), rs.getString(2), rs.getString(3), from, to, null);
                    }
                }
                if (found == 0) {
                    b.line("no file with path '" + path + "'; paths are relative to the project root");
                }
            }
        });
    }

    public String search(String text, String project, int limit, String workspace) {
        return run(workspace, (ws, c, b) -> {
            int max = Math.max(1, Math.min(limit, 50));
            String quoted = "\"" + text.replace("\"", "\"\"") + "\"";
            try (PreparedStatement ps = c.prepareStatement("SELECT f.project_id AS project, f.path, "
                    + "snippet(code_fts, 1, '[', ']', '…', 12) AS snippet FROM code_fts JOIN files f ON f.id = code_fts.rowid "
                    + "WHERE code_fts MATCH ? AND (? IS NULL OR f.project_id = ?) ORDER BY f.project_id, f.path LIMIT ?")) {
                Jdbc.bind(ps, new Object[]{quoted, project, project, max});
                try (ResultSet rs = ps.executeQuery()) {
                    if (Jdbc.table(rs, b, max) == 0) {
                        b.line("no matches (full-text search covers indexed text files; source may be absent in --no-source indexes)");
                    }
                }
            }
        });
    }

    public String callers(String name, String ownerFqn, String project, String workspace) {
        return run(workspace, (ws, c, b) -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT f.project_id AS project, f.path, cl.line, "
                    + "COALESCE(ct.fqn, '') AS caller_type, COALESCE(cm.signature, '') AS caller_member, cl.resolved "
                    + "FROM calls cl JOIN files f ON f.id = cl.file_id "
                    + "LEFT JOIN members cm ON cm.id = cl.caller_member_id LEFT JOIN types ct ON ct.id = cm.type_id "
                    + "WHERE cl.callee_name = ? AND (? IS NULL OR cl.callee_owner_fqn = ?) "
                    + "AND (? IS NULL OR f.project_id = ?) ORDER BY f.project_id, f.path, cl.line LIMIT 100")) {
                Jdbc.bind(ps, new Object[]{name, blankToNull(ownerFqn), blankToNull(ownerFqn), project, project});
                try (ResultSet rs = ps.executeQuery()) {
                    if (Jdbc.table(rs, b, 100) == 0) {
                        b.line("no calls named '" + name + "'");
                    }
                }
            }
        });
    }

    public String sql(String query, String workspace) {
        String checked = SqlGuard.check(query);
        return run(workspace, (ws, c, b) -> {
            int maxRows = properties.sqlMaxRows();
            try (PreparedStatement ps = c.prepareStatement(checked)) {
                ps.setQueryTimeout(properties.sqlTimeoutSeconds());
                ps.setMaxRows(maxRows + 1);
                try (ResultSet rs = ps.executeQuery()) {
                    int rows = Jdbc.table(rs, b, maxRows);
                    b.line("(" + rows + " rows)");
                }
            }
        });
    }

    private interface Formatter {
        String format(ResultSet rs) throws SQLException;
    }

    private static void inline(Budget b, String title, Connection c, String sql, Formatter formatter, Object... params)
            throws SQLException {
        List<String> items = new ArrayList<>();
        Jdbc.query(c, sql, rs -> items.add(formatter.format(rs)), params);
        if (!items.isEmpty()) {
            b.line(title + " " + String.join(", ", items));
        }
    }

    private static String packageOf(String fqn) {
        StringBuilder pkg = new StringBuilder();
        for (String part : fqn.split("\\.")) {
            if (part.isEmpty() || !Character.isLowerCase(part.charAt(0))) {
                break;
            }
            if (pkg.length() > 0) {
                pkg.append('.');
            }
            pkg.append(part);
        }
        return pkg.toString();
    }

    /** Drops java.lang/util/io/sql/time/nio.file prefixes and the type's own package from class names. */
    private static String shorten(String text, String pkg) {
        String result = JDK_PREFIX.matcher(text).replaceAll("");
        if (!pkg.isEmpty()) {
            result = result.replaceAll("\\b" + Pattern.quote(pkg) + "\\.(?=[A-Z])", "");
        }
        return result;
    }

    private static void section(Budget b, String title, Connection c, String sql, Formatter formatter, Object... params)
            throws SQLException {
        List<String> lines = new ArrayList<>();
        Jdbc.query(c, sql, rs -> lines.add(formatter.format(rs)), params);
        if (lines.isEmpty()) {
            return;
        }
        b.line(title);
        for (String line : lines) {
            if (!b.line(line)) {
                return;
            }
        }
    }

    private static List<TypeRow> findExact(Connection c, String fqn, String project) throws SQLException {
        List<TypeRow> rows = new ArrayList<>();
        Jdbc.query(c, "SELECT t.id, f.project_id, t.kind, t.modifiers, f.path, t.line_from, t.line_to, t.doc_summary, "
                + "t.simple_name FROM types t JOIN files f ON f.id = t.file_id WHERE t.fqn = ? "
                + "AND (? IS NULL OR f.project_id = ?) ORDER BY f.project_id", rs -> rows.add(new TypeRow(rs.getLong(1),
                rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6), rs.getInt(7),
                rs.getString(8), rs.getString(9))), fqn, project, project);
        return rows;
    }

    private static void printSource(Budget b, String project, String path, String content, int from, int to,
                                    String label) {
        String header = "// " + project + " " + path + ":" + Math.max(from, 1) + "-" + to + (label == null ? "" : " " + label);
        if (content == null) {
            b.line(header);
            b.line("// source text is not stored in this index (built with --no-source)");
            return;
        }
        String[] lines = content.split("\n", -1);
        int start = Math.max(from, 1);
        int end = Math.min(Math.min(to, lines.length), start + MAX_LINES - 1);
        if (!b.line(header + (end < to ? " (shown to line " + end + ")" : ""))) {
            return;
        }
        for (int i = start; i <= end; i++) {
            if (!b.line(lines[i - 1])) {
                return;
            }
        }
    }

    private static Map<String, String> meta(Connection c, String scope) throws SQLException {
        Map<String, String> map = new LinkedHashMap<>();
        Jdbc.query(c, "SELECT key, value FROM meta WHERE scope = ?", rs -> {
            map.put(rs.getString(1), rs.getString(2));
            return true;
        }, scope);
        return map;
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}