package com.leanowtech.bloge.gateway.visual.runtime;

import java.time.Instant;

/**
 * Exact input/output and failure facts captured for one node invocation attempt.
 */
public record VisualNodeExecutionAttempt(
        int attempt,
        Object input,
        Object output,
        String status,
        Instant startedAt,
        long elapsedMs,
        String errorType,
        String errorMessage
) {
    public VisualNodeExecutionAttempt {
        attempt = Math.max(0, attempt);
        status = status == null || status.isBlank() ? "UNKNOWN" : status;
        startedAt = startedAt == null ? Instant.EPOCH : startedAt;
        elapsedMs = Math.max(0, elapsedMs);
        errorType = errorType == null ? "" : errorType;
        errorMessage = errorMessage == null ? "" : errorMessage;
    }
}
