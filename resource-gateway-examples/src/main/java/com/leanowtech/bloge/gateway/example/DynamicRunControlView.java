package com.leanowtech.bloge.gateway.example;

import java.time.Instant;

/**
 * Observable lifecycle and termination proof for one controlled run.
 *
 * @param schemaVersion control view schema version
 * @param requestId caller-visible run request id
 * @param engineExecutionId BLOGE execution id once observed
 * @param status lifecycle status
 * @param reasonCode stable transition reason
 * @param revision monotonic state revision used by fenced commands
 * @param deadlineAt absolute requested deadline
 * @param startedAt execution start time
 * @param cancelRequestedAt cancellation/deadline transition time
 * @param terminalAt time the owner execution thread was observed to exit
 * @param terminationConfirmed whether the owner execution thread has exited
 * @param sideEffectsMayBeInFlight whether an unconfirmed operator may still commit externally
 */
public record DynamicRunControlView(
        String schemaVersion,
        String requestId,
        String engineExecutionId,
        String status,
        String reasonCode,
        long revision,
        Instant deadlineAt,
        Instant startedAt,
        Instant cancelRequestedAt,
        Instant terminalAt,
        boolean terminationConfirmed,
        boolean sideEffectsMayBeInFlight
) {
    public static final String SCHEMA_VERSION = "resourceGateway.dynamicRunControl.v1";

    public DynamicRunControlView {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        requestId = requestId == null ? "" : requestId;
        engineExecutionId = engineExecutionId == null ? "" : engineExecutionId;
        status = normalized(status, "UNMANAGED");
        reasonCode = normalized(reasonCode, "NONE");
        revision = Math.max(0, revision);
    }

    public static DynamicRunControlView unmanaged() {
        return new DynamicRunControlView("", "", "", "UNMANAGED", "NONE", 0,
                null, null, null, null, true, false);
    }

    public boolean terminal() {
        return switch (status) {
            case "SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "REJECTED" -> true;
            default -> false;
        };
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank()
                ? fallback
                : value.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
