package com.leanowtech.bloge.gateway.authoring.workspace;

/** Request to materialize one immutable seed as a durable, internally current workspace. */
public record WorkspaceForkCommand(
        String schemaVersion,
        WorkspaceSeedBundle seed,
        String workspaceName,
        String changeSource
) {
    /** Current command protocol version. */
    public static final String SCHEMA_VERSION = "bloge.workspaceForkCommand.v1";

    public WorkspaceForkCommand {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        workspaceName = workspaceName == null ? "" : workspaceName.trim();
        changeSource = changeSource == null || changeSource.isBlank()
                ? "author-workspace" : changeSource.trim();
    }
}
