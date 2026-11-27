package ru.mcs.mcpsql.workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.mcs.indexer.db.Schema;
import ru.mcs.mcpsql.config.MultiUserProperties;
import ru.mcs.mcpsql.config.ServiceProperties;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceServiceMultiTest {

    @TempDir
    Path tmp;

    private ServiceProperties properties;
    private WorkspaceService service;

    @BeforeEach
    void setUp() throws IOException {
        properties = new ServiceProperties(tmp.resolve("data"), "r", "w", 8000, 3, 200, 50);
        service = new WorkspaceService(properties, MultiUserProperties.of(true, 50, 5).withLimits(2, 1));
    }

    private Path indexDb(String fileName, int padBytes) throws Exception {
        Path file = tmp.resolve(fileName);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE meta (scope TEXT NOT NULL, key TEXT NOT NULL, value TEXT, PRIMARY KEY (scope, key))");
            s.execute("INSERT INTO meta VALUES ('', 'schema_version', '" + Schema.VERSION + "')");
            s.execute("INSERT INTO meta VALUES ('', 'db_id', 'test-db')");
            s.execute("INSERT INTO meta VALUES ('', 'seq', '1')");
            s.execute("CREATE TABLE projects (slug TEXT)");
            s.execute("INSERT INTO projects VALUES ('demo')");
            if (padBytes > 0) {
                s.execute("CREATE TABLE pad (b BLOB)");
                s.execute("INSERT INTO pad VALUES (zeroblob(" + padBytes + "))");
            }
        }
        return file;
    }

    private WorkspaceInfo upload(String workspace) throws Exception {
        Path file = indexDb("up-" + System.nanoTime() + ".db", 0);
        try (InputStream in = Files.newInputStream(file)) {
            return service.putDatabase(workspace, in);
        }
    }

    @Test
    void ownersSeeOnlyTheirOwnWorkspaces() throws Exception {
        WorkspaceInfo info = upload("1/a");

        assertThat(info.workspace()).isEqualTo("a");
        assertThat(info.projects()).containsExactly("demo");
        assertThat(service.list(1)).containsExactly("a");
        assertThat(service.list(2)).isEmpty();
        assertThat(service.dbPath("1/a")).startsWith(tmp.resolve("data/users/1"));
        assertThatThrownBy(() -> service.resolve("2/a")).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.info("2/a")).isInstanceOf(NoSuchElementException.class);
        assertThat(service.info("1/a").workspace()).isEqualTo("a");
    }

    @Test
    void addressesThatCouldReachOtherFilesAreRefused() {
        for (String raw : List.of("1/../x", "1/a/b", "x/a", "/a", "1/", "99999999999999999999/a", "a", "1/a b")) {
            assertThatThrownBy(() -> service.dbPath(raw))
                    .as(raw).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void localModeRefusesOwnerPrefixes() throws Exception {
        WorkspaceService local = new WorkspaceService(properties);

        assertThat(local.dbPath("a")).isEqualTo(tmp.resolve("data/a.db"));
        assertThatThrownBy(() -> local.dbPath("1/a")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aUserCannotHoldMoreWorkspacesThanTheLimit() throws Exception {
        upload("1/a");
        upload("1/b");

        assertThatThrownBy(() -> upload("1/c")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at most 2");
        assertThat(service.list(1)).containsExactly("a", "b");

        upload("1/a");
        assertThat(service.list(1)).containsExactly("a", "b");

        upload("2/c");
        assertThat(service.list(2)).containsExactly("c");
    }

    @Test
    void aDatabaseOverTheSizeLimitIsRefusedWithoutLeftovers() throws Exception {
        Path big = indexDb("big.db", 2_000_000);

        assertThatThrownBy(() -> {
            try (InputStream in = Files.newInputStream(big)) {
                service.putDatabase("1/big", in);
            }
        }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("larger than 1 MB");

        assertThat(service.list(1)).isEmpty();
        try (Stream<Path> files = Files.list(tmp.resolve("data"))) {
            assertThat(files.filter(p -> p.getFileName().toString().endsWith(".part")).toList()).isEmpty();
        }
    }

    @Test
    void anEmptyNamePicksTheOnlyWorkspaceOfTheOwner() throws Exception {
        upload("1/a");

        assertThat(service.resolve("1/")).isEqualTo("1/a");
        assertThat(service.resolve("1/a")).isEqualTo("1/a");

        upload("1/b");
        assertThatThrownBy(() -> service.resolve("1/")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[a, b]");
        assertThatThrownBy(() -> service.resolve("2/")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no workspace");
    }

    @Test
    void deleteAndDeleteAllOnlyTouchTheirOwner() throws Exception {
        upload("1/a");
        upload("1/b");
        upload("2/a");

        service.delete("1/a");
        assertThat(service.list(1)).containsExactly("b");
        assertThatThrownBy(() -> service.delete("1/a")).isInstanceOf(NoSuchElementException.class);

        upload("1/a");
        assertThat(service.deleteAllOf(1)).isEqualTo(2);
        assertThat(service.list(1)).isEmpty();
        assertThat(service.list(2)).containsExactly("a");
        assertThat(service.deleteAllOf(1)).isZero();
    }

    @Test
    void displayNameStripsTheOwner() {
        assertThat(service.displayName("12/manuscriptium")).isEqualTo("manuscriptium");
        assertThat(service.displayName("plain")).isEqualTo("plain");
    }
}
