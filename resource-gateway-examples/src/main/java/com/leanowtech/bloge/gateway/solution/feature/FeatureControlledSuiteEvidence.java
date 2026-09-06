package com.leanowtech.bloge.gateway.solution.feature;

import java.util.regex.Pattern;

/** Payload-free current evidence produced by one controlled Feature suite run. */
public record FeatureControlledSuiteEvidence(
        String featureRef,
        long suiteRevision,
        String status,
        String evidenceFingerprint,
        String executionEvidenceFingerprint,
        String evaluationRefFingerprint,
        int caseCount,
        int passedCount,
        int failedCount,
        int realExternalCalls,
        Coverage coverage
) {
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates the immutable evidence coordinate and aggregate counts. */
    public FeatureControlledSuiteEvidence {
        featureRef = required(featureRef, "featureRef");
        status = required(status, "status");
        evidenceFingerprint = fingerprint(evidenceFingerprint, "evidenceFingerprint");
        executionEvidenceFingerprint = fingerprint(
                executionEvidenceFingerprint, "executionEvidenceFingerprint");
        evaluationRefFingerprint = fingerprint(evaluationRefFingerprint, "evaluationRefFingerprint");
        if (suiteRevision < 1 || caseCount < 1 || passedCount < 0 || failedCount < 0
                || passedCount + failedCount != caseCount || realExternalCalls < 0 || coverage == null) {
            throw new IllegalArgumentException("Feature suite evidence counts are invalid");
        }
    }

    /** Aggregate coverage proved from case-declared obligations and runner-observed targets. */
    public record Coverage(int targetsTotal, int targetsCovered, int percent, int requiredPercent) {
        /** Rejects impossible coverage projections. */
        public Coverage {
            if (targetsTotal < 1 || targetsCovered < 0 || targetsCovered > targetsTotal
                    || percent < 0 || percent > 100 || requiredPercent < 1 || requiredPercent > 100) {
                throw new IllegalArgumentException("Feature suite coverage is invalid");
            }
        }
    }

    private static String fingerprint(String value, String field) {
        String normalized = required(value, field);
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be a SHA-256 fingerprint");
        }
        return normalized;
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
