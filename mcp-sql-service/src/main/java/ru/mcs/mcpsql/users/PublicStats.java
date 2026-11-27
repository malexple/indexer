package ru.mcs.mcpsql.users;

import java.util.Map;

/** Numbers for the public statistics page; nothing here identifies a person. */
public record PublicStats(long registered, long activeUsers, Map<String, Long> activePairs) {
}
