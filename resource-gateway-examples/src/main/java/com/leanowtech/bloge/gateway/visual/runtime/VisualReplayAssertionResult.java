package com.leanowtech.bloge.gateway.visual.runtime;

/** Sanitized assertion outcome retained with a recorded replay run. */
public record VisualReplayAssertionResult(
        String assertionId,
        String scope,
        String nodeId,
        String mode,
        String path,
        boolean passed,
        String expectedFingerprint,
        String actualFingerprint,
        String message
) {
    public VisualReplayAssertionResult {
        assertionId = normalize(assertionId);
        scope = normalize(scope).toUpperCase();
        nodeId = normalize(nodeId);
        mode = normalize(mode).toUpperCase();
        path = normalize(path);
        expectedFingerprint = normalize(expectedFingerprint);
        actualFingerprint = normalize(actualFingerprint);
        message = normalize(message);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
