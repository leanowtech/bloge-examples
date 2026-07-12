package com.leanowtech.bloge.gateway.integration;

import java.util.List;

/** One stable page from a bounded integration event window. */
public record IntegrationChangeFeed(
        String schemaVersion,
        List<IntegrationChangeEvent> events,
        String nextCursor,
        String checkpointCursor,
        boolean hasMore,
        int itemCount
) {
    public static final String SCHEMA_VERSION = "toolStudio.resourceGateway.changeFeed.v1";

    public IntegrationChangeFeed {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        events = events == null ? List.of() : List.copyOf(events);
        nextCursor = nextCursor == null ? "" : nextCursor;
        checkpointCursor = checkpointCursor == null ? "" : checkpointCursor;
        itemCount = events.size();
    }
}
