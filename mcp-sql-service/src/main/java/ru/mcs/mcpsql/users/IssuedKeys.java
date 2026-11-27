package ru.mcs.mcpsql.users;

import java.time.Instant;

/** The plain keys are shown to the user once; only their hashes are stored. */
public record IssuedKeys(long userId, String readKey, String writeKey, String statsToken, TtlKind ttl,
                         Instant expiresAt) {

    @Override
    public String toString() {
        return "IssuedKeys[userId=" + userId + ", ttl=" + ttl.label() + ", expiresAt=" + expiresAt + ", keys hidden]";
    }
}
