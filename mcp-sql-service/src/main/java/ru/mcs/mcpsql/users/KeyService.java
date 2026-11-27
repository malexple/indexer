package ru.mcs.mcpsql.users;

import ru.mcs.mcpsql.config.MultiUserProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Issues, checks and revokes key pairs. Only SHA-256 hashes of the keys are stored. The caller passes the
 * current time, which keeps expiry testable. Writes are serialised in this process; SQLite does the rest.
 */
public class KeyService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserDb db;
    private final MultiUserProperties properties;

    public KeyService(UserDb db, MultiUserProperties properties) {
        this.db = db;
        this.properties = properties;
    }

    /** A new pair replaces the active one of the same user and starts a fresh lifetime. */
    public synchronized IssuedKeys issue(long tgId, TtlKind ttl, Instant now) {
        long t = now.getEpochSecond();
        String day = day(now);
        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            try {
                update(c, "INSERT OR IGNORE INTO users(tg_id, created_at) VALUES (?, ?)", tgId, t);
                long userId = longOf(c, "SELECT id FROM users WHERE tg_id = ?", tgId);
                if (longOf(c, "SELECT banned FROM users WHERE id = ?", userId) != 0) {
                    throw new QuotaException(QuotaException.Kind.BANNED, "this account is blocked");
                }
                long issuedToday = longOf(c,
                        "SELECT COALESCE(SUM(key_issues), 0) FROM usage_daily WHERE user_id = ? AND day = ?",
                        userId, day);
                if (issuedToday >= properties.keyIssuesPerDay()) {
                    throw new QuotaException(QuotaException.Kind.KEY_ISSUE_LIMIT,
                            "key issue limit reached for today (" + properties.keyIssuesPerDay() + ")");
                }
                boolean hasActive = longOf(c, "SELECT COUNT(*) FROM key_pairs "
                        + "WHERE user_id = ? AND revoked_at IS NULL AND expires_at > ?", userId, t) > 0;
                if (!hasActive && activeUsers(c, t) >= properties.maxActiveUsers()) {
                    throw new QuotaException(QuotaException.Kind.CAPACITY,
                            "the server is full, please come back later");
                }
                update(c, "UPDATE key_pairs SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL", t, userId);

                String readKey = "mcp_r_" + token();
                String writeKey = "mcp_w_" + token();
                String statsToken = "mcp_s_" + token();
                long expires = t + ttl.duration().getSeconds();
                update(c, "INSERT INTO key_pairs(user_id, ttl_kind, read_hash, write_hash, stats_hash, "
                                + "created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        userId, ttl.label(), sha256(readKey), sha256(writeKey), sha256(statsToken), t, expires);
                update(c, "INSERT INTO usage_daily(user_id, day, key_issues) VALUES (?, ?, 1) "
                        + "ON CONFLICT(user_id, day) DO UPDATE SET key_issues = key_issues + 1", userId, day);
                c.commit();
                return new IssuedKeys(userId, readKey, writeKey, statsToken, ttl, Instant.ofEpochSecond(expires));
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot issue keys: " + e.getMessage(), e);
        }
    }

    /** The owner and scope of a valid key; empty for unknown, expired, revoked or blocked keys. */
    public Optional<Principal> authenticate(String token, Instant now) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        byte[] hash = sha256(token.strip());
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT p.id, p.user_id, p.read_hash = ? "
                     + "FROM key_pairs p JOIN users u ON u.id = p.user_id "
                     + "WHERE (p.read_hash = ? OR p.write_hash = ?) AND p.revoked_at IS NULL "
                     + "AND p.expires_at > ? AND u.banned = 0")) {
            ps.setBytes(1, hash);
            ps.setBytes(2, hash);
            ps.setBytes(3, hash);
            ps.setLong(4, now.getEpochSecond());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Scope scope = rs.getInt(3) == 1 ? Scope.READ : Scope.WRITE;
                return Optional.of(new Principal(rs.getLong(2), scope, rs.getLong(1)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot check a key: " + e.getMessage(), e);
        }
    }

    /** The user behind a statistics link, while that pair of keys is alive. */
    public Optional<Long> userByStatsToken(String token, Instant now) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT p.user_id FROM key_pairs p "
                     + "JOIN users u ON u.id = p.user_id WHERE p.stats_hash = ? AND p.revoked_at IS NULL "
                     + "AND p.expires_at > ? AND u.banned = 0")) {
            ps.setBytes(1, sha256(token.strip()));
            ps.setLong(2, now.getEpochSecond());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot check a link: " + e.getMessage(), e);
        }
    }

    /**
     * Revokes every key of the user and gives back today's key issues, so a compromised user can start again.
     * Returns the user id so the caller can delete the user's databases.
     */
    public synchronized Optional<Long> reset(long tgId, Instant now) {
        long t = now.getEpochSecond();
        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            try {
                List<Long> ids = longs(c, "SELECT id FROM users WHERE tg_id = ?", tgId);
                if (ids.isEmpty()) {
                    c.rollback();
                    return Optional.empty();
                }
                long userId = ids.get(0);
                update(c, "UPDATE key_pairs SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL", t, userId);
                update(c, "UPDATE usage_daily SET key_issues = 0 WHERE user_id = ? AND day = ?", userId, day(now));
                c.commit();
                return Optional.of(userId);
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot reset: " + e.getMessage(), e);
        }
    }

    /** Users that had keys once and have no live pair now; their databases are due for deletion. */
    public List<Long> usersWithoutActiveKeys(Instant now) {
        try (Connection c = db.open()) {
            return longs(c, "SELECT u.id FROM users u "
                    + "WHERE EXISTS (SELECT 1 FROM key_pairs p WHERE p.user_id = u.id) "
                    + "AND NOT EXISTS (SELECT 1 FROM key_pairs p WHERE p.user_id = u.id "
                    + "AND p.revoked_at IS NULL AND p.expires_at > ?) ORDER BY u.id", now.getEpochSecond());
        } catch (SQLException e) {
            throw new IllegalStateException("cannot list users: " + e.getMessage(), e);
        }
    }

    public PublicStats stats(Instant now) {
        long t = now.getEpochSecond();
        try (Connection c = db.open()) {
            Map<String, Long> pairs = new LinkedHashMap<>();
            for (TtlKind kind : TtlKind.values()) {
                pairs.put(kind.label(), 0L);
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT ttl_kind, COUNT(*) FROM key_pairs "
                    + "WHERE revoked_at IS NULL AND expires_at > ? GROUP BY ttl_kind")) {
                ps.setLong(1, t);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        pairs.put(rs.getString(1), rs.getLong(2));
                    }
                }
            }
            return new PublicStats(longOf(c, "SELECT COUNT(*) FROM users"), activeUsers(c, t), pairs);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read statistics: " + e.getMessage(), e);
        }
    }

    private static long activeUsers(Connection c, long t) throws SQLException {
        return longOf(c, "SELECT COUNT(DISTINCT user_id) FROM key_pairs WHERE revoked_at IS NULL AND expires_at > ?", t);
    }

    private static String day(Instant now) {
        return LocalDate.ofInstant(now, ZoneOffset.UTC).toString();
    }

    private static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            ps.executeUpdate();
        }
    }

    private static long longOf(Connection c, String sql, Object... params) throws SQLException {
        List<Long> values = longs(c, sql, params);
        return values.isEmpty() ? 0 : values.get(0);
    }

    private static List<Long> longs(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            List<Long> values = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    values.add(rs.getLong(1));
                }
            }
            return values;
        }
    }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }
}
