package ru.mcs.mcpsql.users;

import java.time.Duration;

/** How long a pair of keys (and with it the user's databases) lives. */
public enum TtlKind {
    H5("5h", Duration.ofHours(5)),
    D1("1d", Duration.ofDays(1)),
    D7("7d", Duration.ofDays(7));

    private final String label;
    private final Duration duration;

    TtlKind(String label, Duration duration) {
        this.label = label;
        this.duration = duration;
    }

    public String label() {
        return label;
    }

    public Duration duration() {
        return duration;
    }

    public static TtlKind parse(String value) {
        for (TtlKind kind : values()) {
            if (kind.label.equalsIgnoreCase(value) || kind.name().equalsIgnoreCase(value)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("unknown ttl '" + value + "', use 5h, 1d or 7d");
    }
}
