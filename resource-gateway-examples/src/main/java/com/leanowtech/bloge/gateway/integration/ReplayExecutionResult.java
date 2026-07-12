package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.visual.runtime.VisualReplayAssertionResult;

import java.util.List;

/** Result and lineage of a persisted recorded replay run. */
public record ReplayExecutionResult(
        String schemaVersion,
        String replayRunId,
        String parentRunId,
        String requestId,
        String requestFingerprint,
        String replayMode,
        String caseType,
        String status,
        String sideEffectPolicy,
        int externalInvocationCount,
        List<VisualReplayAssertionResult> assertionResults,
        String evidenceStatus,
        String evidencePath
) {
    public static final String SCHEMA_VERSION = "toolStudio.resourceGateway.replayExecutionResult.v1";

    public ReplayExecutionResult {
        schemaVersion = normalize(schemaVersion).isBlank() ? SCHEMA_VERSION : normalize(schemaVersion);
        replayRunId = normalize(replayRunId);
        parentRunId = normalize(parentRunId);
        requestId = normalize(requestId);
        requestFingerprint = normalize(requestFingerprint);
        replayMode = normalize(replayMode).toUpperCase();
        caseType = normalize(caseType).toUpperCase();
        status = normalize(status).isBlank() ? "UNKNOWN" : normalize(status).toUpperCase();
        sideEffectPolicy = normalize(sideEffectPolicy).isBlank() ? "DENY" : normalize(sideEffectPolicy).toUpperCase();
        externalInvocationCount = Math.max(0, externalInvocationCount);
        assertionResults = assertionResults == null ? List.of() : List.copyOf(assertionResults);
        evidenceStatus = normalize(evidenceStatus).toUpperCase();
        evidencePath = normalize(evidencePath);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
