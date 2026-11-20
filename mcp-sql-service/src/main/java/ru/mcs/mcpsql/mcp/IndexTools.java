package ru.mcs.mcpsql.mcp;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import ru.mcs.mcpsql.query.IndexQueries;

@Component
public class IndexTools {

    private static final String WORKSPACE = "Workspace name; optional when the server holds only one.";
    private static final String PROJECT = "Project slug inside the workspace; optional, omit to search all projects.";

    private final IndexQueries queries;

    public IndexTools(IndexQueries queries) {
        this.queries = queries;
    }

    @McpTool(name = "index_info", annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Start here. Read-only. Shows the workspace projects with commit, "
            + "branch, indexing time, counts and staleness notes. Set includeSchema=true to get the table list for the sql tool.")
    public String indexInfo(
            @McpToolParam(description = WORKSPACE, required = false) String workspace,
            @McpToolParam(description = "Also return the table and column list.", required = false) Boolean includeSchema) {
        return queries.indexInfo(workspace, Boolean.TRUE.equals(includeSchema));
    }

    @McpTool(name = "find_type", annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Find classes, interfaces, enums and records by simple name, "
            + "fully qualified name or name suffix. Returns project, fqn, kind, file path and line range.")
    public String findType(
            @McpToolParam(description = "Simple name, fqn or suffix, e.g. IndexWriter.", required = true) String name,
            @McpToolParam(description = PROJECT, required = false) String project,
            @McpToolParam(description = WORKSPACE, required = false) String workspace) {
        return queries.findType(name, project, workspace);
    }

    @McpTool(name = "context", annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "One-call overview of a type: location, supertypes, subtypes, member "
            + "signatures with line ranges, non-JDK dependencies, dependents and resolved callers. Use it before reading source.")
    public String context(
            @McpToolParam(description = "Fully qualified type name, e.g. ru.mcs.indexer.db.IndexWriter.", required = true) String fqn,
            @McpToolParam(description = PROJECT, required = false) String project,
            @McpToolParam(description = WORKSPACE, required = false) String workspace) {
        return queries.context(fqn, project, workspace);
    }

    @McpTool(name = "read_source", annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Source text of a type, or of the members whose signature starts "
            + "with the given prefix (e.g. 'writeFile('). Cheaper than reading whole files.")
    public String readSource(
            @McpToolParam(description = "Fully qualified type name.", required = true) String fqn,
            @McpToolParam(description = "Member signature prefix; omit to read the whole type.", required = false) String member,
            @McpToolParam(description = PROJECT, required = false) String project,
            @McpToolParam(description = WORKSPACE, required = false) String workspace) {
        return queries.readSource(fqn, member, project, workspace);
    }

    @McpTool(name = "read_lines", annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Lines from..to (1-based, inclusive) of a file by its project-relative path. "
            + "At most 400 lines per call.")
    public String readLines(
            @McpToolParam(description = "Path relative to the project root, e.g. src/main/java/demo/A.java.", required = true) String path,
            @McpToolParam(description = "First line.", required = true) Integer from,
            @McpToolParam(description = "Last line.", required = true) Integer to,
            @McpToolParam(description = PROJECT, required = false) String project,
            @McpToolParam(description = WORKSPACE, required = false) String workspace) {
        return queries.readLines(path, from, to, project, workspace);
    }

    @McpTool(name = "search", annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Full-text search over indexed text files (code, configs, docs). "
            + "The text is matched as a phrase. Returns project, path and a snippet.")
    public String search(
            @McpToolParam(description = "Text to find, e.g. an identifier or a message.", required = true) String text,
            @McpToolParam(description = PROJECT, required = false) String project,
            @McpToolParam(description = "Maximum number of hits, 1..50 (default 10).", required = false) Integer limit,
            @McpToolParam(description = WORKSPACE, required = false) String workspace) {
        return queries.search(text, project, limit == null ? 10 : limit, workspace);
    }

    @McpTool(name = "callers", annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Where a method is called: file, line, calling type and member. "
            + "Pass ownerFqn to keep only calls the solver resolved to that type.")
    public String callers(
            @McpToolParam(description = "Method name, e.g. writeFile.", required = true) String name,
            @McpToolParam(description = "Owner type fqn to restrict to resolved calls.", required = false) String ownerFqn,
            @McpToolParam(description = PROJECT, required = false) String project,
            @McpToolParam(description = WORKSPACE, required = false) String workspace) {
        return queries.callers(name, ownerFqn, project, workspace);
    }

    @McpTool(name = "sql", annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Escape hatch: one read-only SELECT/WITH statement on the index "
            + "(see index_info with includeSchema=true). Output is tab-separated and cut at a size limit; prefer the other tools.")
    public String sql(
            @McpToolParam(description = "A single SELECT or WITH query.", required = true) String query,
            @McpToolParam(description = WORKSPACE, required = false) String workspace) {
        return queries.sql(query, workspace);
    }
}