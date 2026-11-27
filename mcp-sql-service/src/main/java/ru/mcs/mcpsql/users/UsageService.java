package ru.mcs.mcpsql.users;

import ru.mcs.mcpsql.config.MultiUserProperties;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static ru.mcs.mcpsql.users.Sql.longOf;
import static ru.mcs.mcpsql.users.Sql.update;

/**
 * Daily counters per user (UTC day). The check methods run before the work, the record methods after it,
 * also when the work failed, so rejected uploads count too. Checks and records are not one transaction:
 * the limits are soft and may be overshot by one operation.
 */
public class UsageService {

    private final UserDb db;
    private final MultiUserProperties properties;

    public UsageService(UserDb db, MultiUserProperties properties) {
        this.db = db;
        this.properties = properties;
    }

    public synchronized void checkUpload(long userId, Instant now) {
        UsageToday today = today(userId, now);
        if (today.uploads() >= properties.uploadsPerDay()) {
            throw new QuotaException(QuotaException.Kind.UPLOAD_LIMIT,
                    "daily upload limit reached (" + properties.uploadsPerDay() + ")");
        }
        checkBytes(today);
    }

    public synchronized void checkPatch(long userId, Instant now) {
        checkBytes(today(userId, now));
        long wait = lastPatchAt(userId) + properties.patchIntervalSeconds() - now.getEpochSecond();
        if (wait > 0) {
            throw new QuotaException(QuotaException.Kind.PATCH_INTERVAL,
                    "patches are limited to one per " + properties.patchIntervalSeconds() + " seconds, wait " + wait + " s");
        }
    }

    public synchronized void recordUpload(long userId, long bytes, Instant now) {
        write(c -> update(c, "INSERT INTO usage_daily(user_id, day, uploads, upload_bytes) VALUES (?, ?, 1, ?) "
                + "ON CONFLICT(user_id, day) DO UPDATE SET uploads = uploads + 1, "
                + "upload_bytes = upload_bytes + excluded.upload_bytes", userId, day(now), bytes));
    }

    public synchronized void recordPatch(long userId, long bytes, Instant now) {
        write(c -> {
            update(c, "INSERT INTO usage_daily(user_id, day, patches, upload_bytes) VALUES (?, ?, 1, ?) "
                    + "ON CONFLICT(user_id, day) DO UPDATE SET patches = patches + 1, "
                    + "upload_bytes = upload_bytes + excluded.upload_bytes", userId, day(now), bytes);
            update(c, "INSERT INTO user_state(user_id, last_patch_at) VALUES (?, ?) "
                    + "ON CONFLICT(user_id) DO UPDATE SET last_patch_at = excluded.last_patch_at",
                    userId, now.getEpochSecond());
        });
    }

    /** Counts one request and refuses it when the day's limit is used up. */
    public synchronized void recordRequest(long userId, Instant now) {
        write(c -> update(c, "INSERT INTO usage_daily(user_id, day, requests) VALUES (?, ?, 1) "
                + "ON CONFLICT(user_id, day) DO UPDATE SET requests = requests + 1", userId, day(now)));
        if (today(userId, now).requests() > properties.requestsPerDay()) {
            throw new QuotaException(QuotaException.Kind.REQUEST_LIMIT,
                    "daily request limit reached (" + properties.requestsPerDay() + ")");
        }
    }

    public UsageToday today(long userId, Instant now) {
        try (Connection c = db.open();
             var ps = c.prepareStatement("SELECT uploads, patches, upload_bytes, requests, key_issues "
                     + "FROM usage_daily WHERE user_id = ? AND day = ?")) {
            ps.setLong(1, userId);
            ps.setString(2, day(now));
            try (var rs = ps.executeQuery()) {
                return rs.next()
                        ? new UsageToday(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5))
                        : new UsageToday(0, 0, 0, 0, 0);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read usage: " + e.getMessage(), e);
        }
    }

    public Map<String, Long> limits() {
        Map<String, Long> limits = new LinkedHashMap<>();
        limits.put("uploadsPerDay", (long) properties.uploadsPerDay());
        limits.put("writeBytesPerDay", properties.writeMbPerDay() * 1024L * 1024L);
        limits.put("patchIntervalSeconds", (long) properties.patchIntervalSeconds());
        limits.put("requestsPerDay", (long) properties.requestsPerDay());
        limits.put("keyIssuesPerDay", (long) properties.keyIssuesPerDay());
        limits.put("maxWorkspaces", (long) properties.maxWorkspaces());
        limits.put("maxDbBytes", properties.maxDbMb() * 1024L * 1024L);
        return limits;
    }

    private void checkBytes(UsageToday today) {
        if (today.writeBytes() >= properties.writeMbPerDay() * 1024L * 1024L) {
            throw new QuotaException(QuotaException.Kind.WRITE_BYTES_LIMIT,
                    "daily upload volume reached (" + properties.writeMbPerDay() + " MB)");
        }
    }

    private long lastPatchAt(long userId) {
        try (Connection c = db.open()) {
            return longOf(c, "SELECT last_patch_at FROM user_state WHERE user_id = ?", userId);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read usage: " + e.getMessage(), e);
        }
    }

    private interface Work {
        void run(Connection c) throws SQLException;
    }

    private void write(Work work) {
        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            try {
                work.run(c);
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot write usage: " + e.getMessage(), e);
        }
    }

    private static String day(Instant now) {
        return LocalDate.ofInstant(now, ZoneOffset.UTC).toString();
    }
}
