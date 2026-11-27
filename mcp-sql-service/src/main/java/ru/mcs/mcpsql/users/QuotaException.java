package ru.mcs.mcpsql.users;

public class QuotaException extends RuntimeException {

    public enum Kind {
        KEY_ISSUE_LIMIT,
        CAPACITY,
        BANNED
    }

    private final Kind kind;

    public QuotaException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
