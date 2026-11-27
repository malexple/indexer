package ru.mcs.mcpsql.users;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.mcs.mcpsql.config.MultiUserProperties;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsageServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-27T10:00:00Z");
    private static final long MB = 1024L * 1024L;

    @TempDir
    Path tmp;

    private UsageService usage;
    private long alice;
    private long bob;

    @BeforeEach
    void setUp() throws IOException {
        UserDb db = new UserDb(tmp.resolve("system/user.db"));
        MultiUserProperties properties = MultiUserProperties.of(true, 50, 5).withDaily(2, 1, 30, 3);
        KeyService keys = new KeyService(db, properties);
        usage = new UsageService(db, properties);
        alice = keys.issue(1, TtlKind.D1, T0).userId();
        bob = keys.issue(2, TtlKind.D1, T0).userId();
    }

    private static void assertQuota(Runnable action, QuotaException.Kind kind) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(QuotaException.class,
                e -> assertThat(e.kind()).isEqualTo(kind));
    }

    @Test
    void uploadsAreLimitedPerDay() {
        usage.checkUpload(alice, T0);
        usage.recordUpload(alice, 10, T0);
        usage.recordUpload(alice, 10, T0);

        assertQuota(() -> usage.checkUpload(alice, T0), QuotaException.Kind.UPLOAD_LIMIT);
        usage.checkUpload(bob, T0);
        usage.checkUpload(alice, T0.plus(Duration.ofDays(1)));
    }

    @Test
    void theDailyVolumeCoversUploadsAndPatches() {
        usage.recordUpload(alice, MB, T0);

        assertQuota(() -> usage.checkUpload(alice, T0), QuotaException.Kind.WRITE_BYTES_LIMIT);
        assertQuota(() -> usage.checkPatch(alice, T0), QuotaException.Kind.WRITE_BYTES_LIMIT);
        usage.checkPatch(bob, T0);
        usage.checkPatch(alice, T0.plus(Duration.ofDays(1)));
    }

    @Test
    void patchesNeedAPauseBetweenThem() {
        usage.checkPatch(alice, T0);
        usage.recordPatch(alice, 100, T0);

        assertQuota(() -> usage.checkPatch(alice, T0.plusSeconds(10)), QuotaException.Kind.PATCH_INTERVAL);
        usage.checkPatch(alice, T0.plusSeconds(30));
        usage.checkPatch(bob, T0.plusSeconds(10));
    }

    @Test
    void requestsAreCountedAndLimited() {
        usage.recordRequest(alice, T0);
        usage.recordRequest(alice, T0);
        usage.recordRequest(alice, T0);

        assertQuota(() -> usage.recordRequest(alice, T0), QuotaException.Kind.REQUEST_LIMIT);
        usage.recordRequest(bob, T0);
        usage.recordRequest(alice, T0.plus(Duration.ofDays(1)));
    }

    @Test
    void todayShowsTheCountersOfOneUserOnly() {
        usage.recordUpload(alice, 500, T0);
        usage.recordPatch(alice, 200, T0);
        usage.recordRequest(alice, T0);

        UsageToday today = usage.today(alice, T0);
        assertThat(today.uploads()).isEqualTo(1);
        assertThat(today.patches()).isEqualTo(1);
        assertThat(today.writeBytes()).isEqualTo(700);
        assertThat(today.requests()).isEqualTo(1);
        assertThat(today.keyIssues()).isEqualTo(1);
        assertThat(usage.today(bob, T0).writeBytes()).isZero();
        assertThat(usage.today(alice, T0.plus(Duration.ofDays(1))).writeBytes()).isZero();
        assertThat(usage.limits()).containsEntry("uploadsPerDay", 2L).containsEntry("writeBytesPerDay", MB);
    }
}
