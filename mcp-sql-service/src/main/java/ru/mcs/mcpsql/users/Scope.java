package ru.mcs.mcpsql.users;

/** READ is the key for the AI (MCP only); WRITE is the user's own key (upload, patches, delete, and reading). */
public enum Scope {
    READ,
    WRITE
}
