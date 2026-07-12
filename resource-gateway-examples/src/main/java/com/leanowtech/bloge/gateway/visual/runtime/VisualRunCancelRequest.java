package com.leanowtech.bloge.gateway.visual.runtime;

/** HTTP request body for a fenced visual-run cancellation command. */
public record VisualRunCancelRequest(
        String fencingToken,
        long expectedRevision,
        String reason
) {
    public VisualRunCancelRequest {
        fencingToken = fencingToken == null ? "" : fencingToken.trim();
        expectedRevision = Math.max(0, expectedRevision);
        reason = reason == null ? "" : reason.trim();
    }
}
