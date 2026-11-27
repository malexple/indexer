package ru.mcs.mcpsql.users;

import java.time.Instant;

/** The key pair that is alive right now. */
public record ActivePair(TtlKind ttl, Instant createdAt, Instant expiresAt) {
}
