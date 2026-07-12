package com.leanowtech.bloge.gateway.example;

/**
 * Fenced command requesting cooperative cancellation of a live run.
 *
 * @param requestId controlled run request id
 * @param fencingToken token from the immutable run intent
 * @param expectedRevision optional optimistic state revision; zero disables the revision precondition
 * @param reason caller-supplied audit reason
 */
public record DynamicRunControlCommand(
        String requestId,
        String fencingToken,
        long expectedRevision,
        String reason
) {
    public DynamicRunControlCommand {
        requestId = requestId == null ? "" : requestId.trim();
        fencingToken = fencingToken == null ? "" : fencingToken.trim();
        expectedRevision = Math.max(0, expectedRevision);
        reason = reason == null ? "" : reason.trim();
    }
}
