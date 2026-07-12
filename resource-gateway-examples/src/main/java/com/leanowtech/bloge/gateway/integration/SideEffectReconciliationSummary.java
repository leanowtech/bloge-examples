package com.leanowtech.bloge.gateway.integration;

import java.util.List;

/** Effective governance view obtained from immutable run evidence plus signed refinements. */
public record SideEffectReconciliationSummary(
        String schemaVersion,
        String runId,
        String evidenceId,
        String baseEvidenceFingerprint,
        String status,
        String governanceStatus,
        List<String> outstandingAttemptIds,
        List<String> remainingEvidenceGaps,
        List<SideEffectReconciliationRecord> reconciliations
) {
    public static final String SCHEMA_VERSION =
            "toolStudio.resourceGateway.sideEffectReconciliationSummary.v1";

    public SideEffectReconciliationSummary {
        schemaVersion = normalize(schemaVersion).isBlank() ? SCHEMA_VERSION : normalize(schemaVersion);
        runId = normalize(runId);
        evidenceId = normalize(evidenceId);
        baseEvidenceFingerprint = normalize(baseEvidenceFingerprint);
        status = normalize(status).isBlank() ? "OUTSTANDING" : normalize(status);
        governanceStatus = normalize(governanceStatus).isBlank() ? "QUARANTINED" : normalize(governanceStatus);
        outstandingAttemptIds = outstandingAttemptIds == null ? List.of() : List.copyOf(outstandingAttemptIds);
        remainingEvidenceGaps = remainingEvidenceGaps == null ? List.of() : List.copyOf(remainingEvidenceGaps);
        reconciliations = reconciliations == null ? List.of() : List.copyOf(reconciliations);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
