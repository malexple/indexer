package ru.mcs.mcpsql.users;

/** One user's counters for the current UTC day; writeBytes covers database uploads and patches together. */
public record UsageToday(long uploads, long patches, long writeBytes, long requests, long keyIssues) {
}
