package ru.mcs.mcpsql.users;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.mcs.mcpsql.config.MultiUserProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-27T10:00:00Z");

    @TempDir
    Path tmp;

    private UserDb db;
    private KeyService keys;

    @BeforeEach
    void setUp() throws IOException {
        db = new UserDb(tmp.resolve("system/user.db"));
        keys = service(50, 5);
    }

    private KeyService service(int maxUsers, int issuesPerDay) {
        return new KeyService(db, new MultiUserProperties(true, maxUsers, issuesPerDay));
    }

    private static void assertQuota(Runnable action, QuotaException.Kind kind) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(QuotaException.class,
                e -> assertThat(e.kind()).isEqualTo(kind));
    }

    @Test
    void readAndWriteKeysAuthenticateWithTheirOwnScope() {
        IssuedKeys issued = keys.issue(111, TtlKind.D1, T0);

        Principal read = keys.authenticate(issued.readKey(), T0).orElseThrow();
        Principal write = keys.authenticate(issued.writeKey(), T0).orElseThrow();
        assertThat(read.scope()).isEqualTo(Scope.READ);
        assertThat(write.scope()).isEqualTo(Scope.WRITE);
        assertThat(read.userId()).isEqualTo(write.userId()).isEqualTo(issued.userId());
        assertThat(keys.authenticate("mcp_r_wrong", T0)).isEmpty();
        assertThat(keys.authenticate(issued.statsToken(), T0)).isEmpty();
        assertThat(keys.authenticate("", T0)).isEmpty();
        assertThat(keys.authenticate(null, T0)).isEmpty();
    }

    @Test
    void onlyHashesAreStored() throws Exception {
        IssuedKeys issued = keys.issue(111, TtlKind.D1, T0);

        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM key_pairs WHERE read_hash = ?")) {
            ps.setBytes(1, issued.readKey().getBytes(StandardCharsets.UTF_8));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getLong(1)).isZero();
            }
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT length(read_hash) FROM key_pairs")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(32);
            }
        }
        assertThat(issued.toString()).doesNotContain(issued.readKey()).doesNotContain(issued.writeKey());
    }

    @Test
    void keysExpireAtTheEndOfTheirLifetime() {
        IssuedKeys fiveHours = keys.issue(1, TtlKind.H5, T0);
        IssuedKeys day = keys.issue(2, TtlKind.D1, T0);
        IssuedKeys week = keys.issue(3, TtlKind.D7, T0);

        assertThat(fiveHours.expiresAt()).isEqualTo(T0.plus(Duration.ofHours(5)));
        assertThat(day.expiresAt()).isEqualTo(T0.plus(Duration.ofDays(1)));
        assertThat(week.expiresAt()).isEqualTo(T0.plus(Duration.ofDays(7)));

        Instant justBefore = T0.plus(Duration.ofHours(5)).minusSeconds(1);
        Instant atExpiry = T0.plus(Duration.ofHours(5));
        assertThat(keys.authenticate(fiveHours.readKey(), justBefore)).isPresent();
        assertThat(keys.authenticate(fiveHours.readKey(), atExpiry)).isEmpty();
        assertThat(keys.authenticate(day.writeKey(), atExpiry)).isPresent();
        assertThat(keys.authenticate(week.writeKey(), T0.plus(Duration.ofDays(7)))).isEmpty();
    }

    @Test
    void aNewPairReplacesTheOldOneAndRestartsTheLifetime() {
        IssuedKeys first = keys.issue(1, TtlKind.H5, T0);
        Instant later = T0.plus(Duration.ofHours(1));
        IssuedKeys second = keys.issue(1, TtlKind.D1, later);

        assertThat(second.userId()).isEqualTo(first.userId());
        assertThat(keys.authenticate(first.readKey(), later)).isEmpty();
        assertThat(keys.authenticate(first.writeKey(), later)).isEmpty();
        assertThat(keys.authenticate(second.readKey(), later)).isPresent();
        assertThat(second.expiresAt()).isEqualTo(later.plus(Duration.ofDays(1)));
    }

    @Test
    void keyIssuesAreLimitedPerDayAndResetGivesThemBack() {
        for (int i = 0; i < 5; i++) {
            keys.issue(1, TtlKind.H5, T0.plusSeconds(i));
        }
        assertQuota(() -> keys.issue(1, TtlKind.H5, T0.plusSeconds(10)), QuotaException.Kind.KEY_ISSUE_LIMIT);

        keys.issue(1, TtlKind.H5, T0.plus(Duration.ofDays(1)));

        for (int i = 0; i < 4; i++) {
            keys.issue(2, TtlKind.H5, T0.plusSeconds(i));
        }
        IssuedKeys fifth = keys.issue(2, TtlKind.H5, T0.plusSeconds(5));
        assertQuota(() -> keys.issue(2, TtlKind.H5, T0.plusSeconds(6)), QuotaException.Kind.KEY_ISSUE_LIMIT);

        long userId = keys.reset(2, T0.plusSeconds(7)).orElseThrow();
        assertThat(userId).isEqualTo(fifth.userId());
        assertThat(keys.authenticate(fifth.readKey(), T0.plusSeconds(7))).isEmpty();
        IssuedKeys again = keys.issue(2, TtlKind.D1, T0.plusSeconds(8));
        assertThat(keys.authenticate(again.writeKey(), T0.plusSeconds(8))).isPresent();
    }

    @Test
    void resetOfAnUnknownUserDoesNothing() {
        assertThat(keys.reset(999, T0)).isEmpty();
    }

    @Test
    void theServerStopsTakingNewUsersWhenFull() {
        KeyService small = service(2, 5);
        small.issue(1, TtlKind.H5, T0);
        small.issue(2, TtlKind.H5, T0);

        assertQuota(() -> small.issue(3, TtlKind.H5, T0), QuotaException.Kind.CAPACITY);
        assertThat(small.issue(1, TtlKind.D1, T0.plusSeconds(1))).isNotNull();

        Instant later = T0.plus(Duration.ofHours(6));
        assertThat(small.issue(3, TtlKind.H5, later)).isNotNull();
    }

    @Test
    void aBlockedUserCannotUseOrGetKeys() throws Exception {
        IssuedKeys issued = keys.issue(1, TtlKind.D1, T0);
        try (Connection c = db.open(); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE users SET banned = 1");
        }

        assertThat(keys.authenticate(issued.readKey(), T0)).isEmpty();
        assertThat(keys.userByStatsToken(issued.statsToken(), T0)).isEmpty();
        assertQuota(() -> keys.issue(1, TtlKind.D1, T0.plusSeconds(1)), QuotaException.Kind.BANNED);
    }

    @Test
    void statsTokenFindsTheUserWhileTheKeysLive() {
        IssuedKeys issued = keys.issue(1, TtlKind.H5, T0);

        assertThat(keys.userByStatsToken(issued.statsToken(), T0)).contains(issued.userId());
        assertThat(keys.userByStatsToken(issued.statsToken(), T0.plus(Duration.ofHours(5)))).isEmpty();
        assertThat(keys.userByStatsToken("mcp_s_unknown", T0)).isEmpty();
    }

    @Test
    void publicStatisticsCountPeopleAndPairs() {
        keys.issue(1, TtlKind.H5, T0);
        keys.issue(2, TtlKind.D1, T0);
        keys.issue(3, TtlKind.D7, T0);

        PublicStats now = keys.stats(T0);
        assertThat(now.registered()).isEqualTo(3);
        assertThat(now.activeUsers()).isEqualTo(3);
        assertThat(now.activePairs()).containsEntry("5h", 1L).containsEntry("1d", 1L).containsEntry("7d", 1L);

        PublicStats later = keys.stats(T0.plus(Duration.ofHours(6)));
        assertThat(later.registered()).isEqualTo(3);
        assertThat(later.activeUsers()).isEqualTo(2);
        assertThat(later.activePairs()).containsEntry("5h", 0L).containsEntry("1d", 1L).containsEntry("7d", 1L);
    }

    @Test
    void usersWithoutLiveKeysAreListedForCleanup() {
        IssuedKeys short1 = keys.issue(1, TtlKind.H5, T0);
        keys.issue(2, TtlKind.D1, T0);

        assertThat(keys.usersWithoutActiveKeys(T0)).isEmpty();
        assertThat(keys.usersWithoutActiveKeys(T0.plus(Duration.ofHours(6)))).containsExactly(short1.userId());
    }
}
