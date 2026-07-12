package com.leanowtech.bloge.gateway.visual.runtime;

import java.util.List;

/** Immutable lineage and safety policy for a replay-derived run. */
public record VisualReplayMetadata(
        String schemaVersion,
        String parentRunId,
        String requestId,
        String requestFingerprint,
        String mode,
        String caseType,
        String sideEffectPolicy,
        int externalInvocationCount,
        List<VisualReplayAssertionResult> assertionResults
) {
    public static final String SCHEMA_VERSION = "toolStudio.resourceGateway.visualReplayMetadata.v1";

    public VisualReplayMetadata {
        schemaVersion = normalize(schemaVersion).isBlank() ? SCHEMA_VERSION : normalize(schemaVersion);
        parentRunId = normalize(parentRunId);
        requestId = normalize(requestId);
        requestFingerprint = normalize(requestFingerprint);
        mode = normalize(mode).isBlank() ? "NONE" : normalize(mode).toUpperCase();
        caseType = normalize(caseType).toUpperCase();
        sideEffectPolicy = normalize(sideEffectPolicy).isBlank()
                ? "DENY" : normalize(sideEffectPolicy).toUpperCase();
        externalInvocationCount = Math.max(0, externalInvocationCount);
        assertionResults = assertionResults == null ? List.of() : List.copyOf(assertionResults);
    }

    public static VisualReplayMetadata none() {
        return new VisualReplayMetadata("", "", "", "", "NONE", "", "DENY", 0, List.of());
    }

    public boolean replay() {
        return !parentRunId.isBlank() && !"NONE".equals(mode);
    }

    public boolean assertionsPassed() {
        return !assertionResults.isEmpty() && assertionResults.stream().allMatch(VisualReplayAssertionResult::passed);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
