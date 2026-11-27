package ru.mcs.mcpsql.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import ru.mcs.mcpsql.config.MultiUserProperties;
import ru.mcs.mcpsql.config.ServiceProperties;
import ru.mcs.mcpsql.users.KeyService;
import ru.mcs.mcpsql.users.UsageService;
import ru.mcs.mcpsql.users.UserDb;
import ru.mcs.mcpsql.workspace.WorkspaceService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InternalControllerTest {

    @TempDir
    Path tmp;

    private InternalController controller;
    private WorkspaceService workspaces;

    @BeforeEach
    void setUp() throws Exception {
        MultiUserProperties multi = MultiUserProperties.of(true, 50, 2).withMinFreeDiskMb(0);
        ServiceProperties properties = new ServiceProperties(tmp.resolve("data"), "r", "w", 8000, 3, 200, 50);
        UserDb db = new UserDb(tmp.resolve("data/system/user.db"));
        workspaces = new WorkspaceService(properties, multi);
        controller = new InternalController(new KeyService(db, multi), workspaces, new UsageService(db, multi));
    }

    @Test
    void anUnknownUserHasNothing() throws Exception {
        assertThat(controller.me(777)).containsEntry("registered", false).doesNotContainKey("active");
    }

    @Test
    void myDataShowsTheLivePairAndTodaysCounters() throws Exception {
        controller.issue(new InternalController.IssueRequest(42, "1d"));

        Map<String, Object> me = controller.me(42);

        assertThat(me).containsEntry("registered", true).containsEntry("active", true).containsEntry("ttl", "1d");
        assertThat(me.get("expiresAt")).isNotNull();
        assertThat((List<?>) me.get("workspaces")).isEmpty();
        assertThat(me.get("usage")).isNotNull();
        assertThat(((Map<?, ?>) me.get("limits")).get("maxWorkspaces")).isEqualTo(3L);
    }

    @Test
    void theIssueLimitAnswersWithTooManyRequestsAndAResetGivesItBack() throws Exception {
        controller.issue(new InternalController.IssueRequest(42, "5h"));
        controller.issue(new InternalController.IssueRequest(42, "5h"));

        ResponseEntity<Map<String, Object>> refused = controller.issue(new InternalController.IssueRequest(42, "5h"));
        assertThat(refused.getStatusCode().value()).isEqualTo(429);
        assertThat(refused.getBody()).containsEntry("code", "KEY_ISSUE_LIMIT");

        Path file = workspaces.dbPath("1/leftover");
        Files.createDirectories(file.getParent());
        Files.createFile(file);
        Map<String, Object> reset = controller.reset(new InternalController.ResetRequest(42));
        assertThat(reset).containsEntry("reset", true).containsEntry("deletedFiles", 1);
        assertThat(controller.me(42)).containsEntry("active", false);

        assertThat(controller.issue(new InternalController.IssueRequest(42, "5h")).getStatusCode().value())
                .isEqualTo(200);
    }
}
