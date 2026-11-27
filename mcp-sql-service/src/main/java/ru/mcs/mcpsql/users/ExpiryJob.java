package ru.mcs.mcpsql.users;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import ru.mcs.mcpsql.workspace.WorkspaceService;

import java.io.IOException;
import java.time.Instant;

/**
 * Deletes the databases of users whose keys have run out and then the expired key rows. The user row with the
 * Telegram id and the usage counters stays. Directories of unknown owners are removed as well.
 */
public class ExpiryJob {

    private static final Logger LOG = LoggerFactory.getLogger(ExpiryJob.class);

    private final KeyService keys;
    private final WorkspaceService workspaces;

    public ExpiryJob(KeyService keys, WorkspaceService workspaces) {
        this.keys = keys;
        this.workspaces = workspaces;
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    public void run() {
        try {
            int deleted = cleanup(Instant.now());
            if (deleted > 0) {
                LOG.info("expired workspaces deleted: {} files", deleted);
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("cleanup failed: {}", e.getMessage());
        }
    }

    /** Returns the number of database files deleted. */
    public int cleanup(Instant now) throws IOException {
        if (keys.stats(now).registered() == 0) {
            return 0;
        }
        int deleted = 0;
        for (long owner : workspaces.ownersOnDisk()) {
            if (!keys.hasActiveKeys(owner, now)) {
                deleted += workspaces.deleteAllOf(owner);
            }
        }
        for (long userId : keys.usersWithoutActiveKeys(now)) {
            keys.purgeKeys(userId, now);
        }
        return deleted;
    }
}
