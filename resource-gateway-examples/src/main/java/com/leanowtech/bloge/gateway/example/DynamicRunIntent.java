package com.leanowtech.bloge.gateway.example;

import java.time.Instant;

/**
 * Versioned caller intent for one controlled dynamic graph execution.
 *
 * @param schemaVersion intent schema version
 * @param requestId caller-generated id used to address the live run
 * @param deadlineAt absolute deadline; {@code null} means no graph deadline
 * @param fencingToken caller-generated token that fences stale control commands
 * @param cancellationGraceMs time allowed for cooperative termination confirmation
 */
public record DynamicRunIntent(
        String schemaVersion,
        String requestId,
        Instant deadlineAt,
        String fencingToken,
        long cancellationGraceMs
) {
    public static final String SCHEMA_VERSION = "resourceGateway.dynamicRunIntent.v1";
    public static final long DEFAULT_CANCELLATION_GRACE_MS = 2_000;
    public static final long MAX_CANCELLATION_GRACE_MS = 30_000;

    public DynamicRunIntent {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        requestId = requestId == null ? "" : requestId.trim();
        fencingToken = fencingToken == null ? "" : fencingToken.trim();
        cancellationGraceMs = cancellationGraceMs <= 0
                ? DEFAULT_CANCELLATION_GRACE_MS
                : Math.min(cancellationGraceMs, MAX_CANCELLATION_GRACE_MS);
    }

    public static DynamicRunIntent unmanaged() {
        return new DynamicRunIntent("", "", null, "", DEFAULT_CANCELLATION_GRACE_MS);
    }

    public boolean managed() {
        return !requestId.isBlank();
    }

    public boolean requested() {
        return managed() || deadlineAt != null || !fencingToken.isBlank();
    }
}
