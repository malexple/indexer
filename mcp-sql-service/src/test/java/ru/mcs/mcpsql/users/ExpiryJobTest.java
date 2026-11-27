package ru.mcs.mcpsql.users;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.mcs.indexer.db.Schema;
import ru.mcs.mcpsql.config.MultiUserProperties;
import ru.mcs.mcpsql.config.ServiceProperties;
import ru.mcs.mcpsql.workspace.WorkspaceService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ExpiryJobTest {

    private static final Instant T0 = Instant.parse("2026-11-27T10:00:00Z");

    @TempDir
    Path tmp;

    private MultiUserProperties properties;
    private ServiceProperties service;
    private UserDb db;
    private KeyService keys;
    private WorkspaceService workspaces;
    private ExpiryJob job;

    @BeforeEach
    void setUp() throws IOException {
        properties = MultiUserProperties.of(true, 50, 5).withMinFreeDiskMb(0);
        service = new ServiceProperties(tmp.resolve("data"), "r", "w", 8000, 3, 200, 50);
        db = new UserDb(tmp.resolve("data/system/user.db"));
        keys = new KeyService(db, properties);
        workspaces = new WorkspaceService(service, properties);
        job = new ExpiryJob(keys, workspaces);
    }

    private void upload(long owner, String name) throws Exception {
        Path file = tmp.resolve("up-" + System.nanoTime() + ".db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE meta (scope TEXT NOT NULL, key TEXT NOT NULL, value TEXT, PRIMARY KEY (scope, key))");
            s.execute("INSERT INTO meta VALUES ('', 'schema_version', '" + Schema.VERSION + "')");
            s.execute("INSERT INTO meta VALUES ('', 'db_id', 'test-db')");
            s.execute("CREATE TABLE projects (slug TEXT)");
        }
        try (InputStream in = Files.newInputStream(file)) {
            workspaces.putDatabase(owner + "/" + name, in);
        }
    }

    @Test
    void databasesOfExpiredUsersGoAndTheUserRecordStays() throws Exception {
        long shortLived = keys.issue(1, TtlKind.H5, T0).userId();
        long longLived = keys.issue(2, TtlKind.D7, T0).userId();
        upload(shortLived, "a");
        upload(shortLived, "b");
        upload(longLived, "a");

        assertThat(job.cleanup(T0.plus(Duration.ofHours(1)))).isZero();
        assertThat(workspaces.list(shortLived)).containsExactly("a", "b");

        assertThat(job.cleanup(T0.plus(Duration.ofHours(6)))).isEqualTo(2);
        assertThat(workspaces.list(shortLived)).isEmpty();
        assertThat(workspaces.list(longLived)).containsExactly("a");
        assertThat(workspaces.ownersOnDisk()).containsExactly(longLived);

        Instant later = T0.plus(Duration.ofHours(6));
        assertThat(keys.usersWithoutActiveKeys(later)).isEmpty();
        assertThat(keys.stats(later).registered()).isEqualTo(2);
        assertThat(keys.stats(later).activeUsers()).isEqualTo(1);

        assertThat(job.cleanup(later)).isZero();
    }

    @Test
    void aUserWhoGotNewKeysKeepsTheDatabases() throws Exception {
        long id = keys.issue(1, TtlKind.H5, T0).userId();
        upload(id, "a");
        keys.issue(1, TtlKind.D1, T0.plus(Duration.ofHours(6)));

        assertThat(job.cleanup(T0.plus(Duration.ofHours(7)))).isZero();
        assertThat(workspaces.list(id)).containsExactly("a");
    }

    @Test
    void directoriesOfUnknownOwnersAreRemoved() throws Exception {
        keys.issue(1, TtlKind.D7, T0);
        upload(999, "orphan");

        assertThat(job.cleanup(T0)).isEqualTo(1);
        assertThat(workspaces.ownersOnDisk()).isEmpty();
    }

    @Test
    void anEmptyUserDatabaseDeletesNothing() throws Exception {
        upload(5, "a");

        assertThat(job.cleanup(T0)).isZero();
        assertThat(workspaces.list(5)).containsExactly("a");
    }
}
