package com.leanowtech.bloge.gateway.visual.runtime;

/** Fenced caller command requesting cooperative cancellation. */
public record VisualRunControlCommand(
        String requestId,
        String fencingToken,
        long expectedRevision,
        String reason
) {
    public VisualRunControlCommand {
        requestId = requestId == null ? "" : requestId.trim();
        fencingToken = fencingToken == null ? "" : fencingToken.trim();
        expectedRevision = Math.max(0, expectedRevision);
        reason = reason == null ? "" : reason.trim();
    }
}
