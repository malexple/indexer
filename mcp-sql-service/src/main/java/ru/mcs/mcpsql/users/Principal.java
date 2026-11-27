package ru.mcs.mcpsql.users;

/** Who a key belongs to. */
public record Principal(long userId, Scope scope, long pairId) {
}
