package ru.mcs.mcpsql.workspace;

/**
 * A workspace address: "name" in local mode, "ownerId/name" in multi-user mode. The owner is always put there
 * by the server (from the key), never taken from the client, so a user cannot name another user's files.
 */
public record WorkspaceKey(Long owner, String name) {

    public static WorkspaceKey parse(String raw) {
        if (raw == null) {
            return new WorkspaceKey(null, "");
        }
        String value = raw.strip();
        int slash = value.indexOf('/');
        if (slash < 0) {
            return new WorkspaceKey(null, value);
        }
        String left = value.substring(0, slash);
        String right = value.substring(slash + 1);
        if (left.isEmpty() || left.length() > 18 || !left.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
            throw new IllegalArgumentException("invalid workspace name: " + raw);
        }
        return new WorkspaceKey(Long.parseLong(left), right);
    }

    public static WorkspaceKey of(long owner, String name) {
        return new WorkspaceKey(owner, name);
    }

    public String asString() {
        return owner == null ? name : owner + "/" + name;
    }
}
