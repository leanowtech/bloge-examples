package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.Map;

/** Idempotent command to resolve one immutable UNKNOWN_COMMIT attempt. */
public record SideEffectReconciliationRequest(
        String schemaVersion,
        String requestId,
        String expectedEvidenceFingerprint,
        String expectedAttemptFingerprint
) {
    public static final String SCHEMA_VERSION =
            "toolStudio.resourceGateway.sideEffectReconciliationRequest.v1";

    public SideEffectReconciliationRequest {
        schemaVersion = normalize(schemaVersion).isBlank() ? SCHEMA_VERSION : normalize(schemaVersion);
        requestId = normalize(requestId);
        expectedEvidenceFingerprint = normalize(expectedEvidenceFingerprint);
        expectedAttemptFingerprint = normalize(expectedAttemptFingerprint);
    }

    public String requestFingerprint() {
        return VisualBundleFingerprint.fromMaterial(Map.of(
                "schemaVersion", schemaVersion,
                "requestId", requestId,
                "expectedEvidenceFingerprint", expectedEvidenceFingerprint,
                "expectedAttemptFingerprint", expectedAttemptFingerprint));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
