package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Objects;

/** Shared whole-record fingerprints for comparison authority and classification storage. */
final class ExternalArchiveComparisonStateIntegrity {
    private static final String AUTHORITY_SCHEMA =
            "bloge.testSuiteStabilityObservationExternalComparisonAuthorityState.v1";
    private static final String CLASSIFICATION_ROW_SCHEMA =
            "bloge.testSuiteStabilityObservationExternalClassificationRow.v1";

    private ExternalArchiveComparisonStateIntegrity() {
    }

    /** Returns the canonical fingerprint over every comparison-authority column. */
    static String authorityFingerprint(
            ObjectMapper objectMapper,
            String authorityId,
            String activeComparisonId,
            String lastCompletedComparisonId,
            long revision,
            Instant updatedAt) {
        return ProtocolFingerprint.of(Objects.requireNonNull(objectMapper, "objectMapper"),
                new AuthorityMaterial(AUTHORITY_SCHEMA, authorityId, activeComparisonId,
                        lastCompletedComparisonId, revision, updatedAt));
    }

    /** Returns the canonical fingerprint over every persisted classification column. */
    static String classificationRowFingerprint(
            ObjectMapper objectMapper,
            DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                    .Classification classification,
            long pageSequence,
            Instant committedAt) {
        return ProtocolFingerprint.of(Objects.requireNonNull(objectMapper, "objectMapper"),
                new ClassificationRowMaterial(CLASSIFICATION_ROW_SCHEMA,
                        Objects.requireNonNull(classification, "classification"), pageSequence,
                        committedAt));
    }

    private record AuthorityMaterial(
            String schemaVersion,
            String authorityId,
            String activeComparisonId,
            String lastCompletedComparisonId,
            long revision,
            Instant updatedAt) {
    }

    private record ClassificationRowMaterial(
            String schemaVersion,
            DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                    .Classification classification,
            long pageSequence,
            Instant committedAt) {
    }
}
