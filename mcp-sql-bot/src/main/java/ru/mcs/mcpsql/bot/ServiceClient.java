package ru.mcs.mcpsql.bot;

import java.util.List;

public interface ServiceClient {

    record IssuedKeys(String readKey, String writeKey, String statsToken, String ttl, String expiresAt) {
    }

    record ResetResult(boolean reset, int deletedFiles) {
    }

    record WorkspaceView(String name, long seq, List<String> projects, long bytes) {
    }

    record Usage(long uploads, long patches, long writeBytes, long requests, long keyIssues) {
    }

    record Limits(long uploadsPerDay, long writeBytesPerDay, long patchIntervalSeconds, long requestsPerDay,
                  long keyIssuesPerDay, long maxWorkspaces, long maxDbBytes) {
    }

    record Me(boolean registered, boolean active, String ttl, String expiresAt, List<WorkspaceView> workspaces,
              Usage usage, Limits limits) {
    }

    record Stats(long registered, long activeUsers, long pairs5h, long pairs1d, long pairs7d) {
    }

    IssuedKeys issueKeys(long tgId, String ttl) throws ServiceException;

    ResetResult reset(long tgId) throws ServiceException;

    Me me(long tgId) throws ServiceException;

    Stats stats() throws ServiceException;
}
