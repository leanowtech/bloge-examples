package com.leanowtech.bloge.gateway.visual.runtime;

import java.time.Instant;

/**
 * Visual-runtime-owned execution intent passed through a hosting adapter.
 *
 * @param schemaVersion intent schema version
 * @param requestId caller-generated live-run address
 * @param deadlineAt absolute graph deadline
 * @param fencingToken token fencing stale control commands
 * @param cancellationGraceMs cooperative termination confirmation window
 */
public record VisualRunIntent(
        String schemaVersion,
        String requestId,
        Instant deadlineAt,
        String fencingToken,
        long cancellationGraceMs
) {
    public static final String SCHEMA_VERSION = "bloge.visualRunIntent.v1";
    public static final long DEFAULT_CANCELLATION_GRACE_MS = 2_000;

    public VisualRunIntent {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        requestId = requestId == null ? "" : requestId.trim();
        fencingToken = fencingToken == null ? "" : fencingToken.trim();
        cancellationGraceMs = cancellationGraceMs <= 0 ? DEFAULT_CANCELLATION_GRACE_MS : cancellationGraceMs;
    }

    public static VisualRunIntent unmanaged() {
        return new VisualRunIntent("", "", null, "", DEFAULT_CANCELLATION_GRACE_MS);
    }

    public boolean managed() {
        return !requestId.isBlank();
    }

    public boolean requested() {
        return managed() || deadlineAt != null || !fencingToken.isBlank();
    }
}
