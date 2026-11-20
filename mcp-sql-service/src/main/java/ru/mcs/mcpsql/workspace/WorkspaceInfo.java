package ru.mcs.mcpsql.workspace;

import java.util.List;

public record WorkspaceInfo(String workspace, String dbId, long seq, List<String> projects) {
}