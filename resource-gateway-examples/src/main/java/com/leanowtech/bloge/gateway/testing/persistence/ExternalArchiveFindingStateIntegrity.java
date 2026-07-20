package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Objects;

/** Shared canonical fingerprints for governed finding authority and projection state. */
final class ExternalArchiveFindingStateIntegrity {
    private static final String AUTHORITY_SCHEMA =
            "bloge.testSuiteStabilityObservationExternalArchiveFindingAuthority.v1";
    private static final String PROJECTION_SCHEMA =
            "bloge.testSuiteStabilityObservationExternalArchiveFindingProjection.v1";

    private ExternalArchiveFindingStateIntegrity() {
    }

    /** Returns the canonical fingerprint over every finding-authority column except itself. */
    static String authorityFingerprint(
            ObjectMapper objectMapper,
            String authorityId,
            String activeProjectionId,
            String lastCompletedProjectionId,
            String lastAppliedComparisonId,
            Instant lastAppliedComparisonCompletedAt,
            long revision,
            Instant updatedAt) {
        return ProtocolFingerprint.of(Objects.requireNonNull(objectMapper, "objectMapper"),
                new AuthorityMaterial(AUTHORITY_SCHEMA, authorityId, activeProjectionId,
                        lastCompletedProjectionId, lastAppliedComparisonId,
                        lastAppliedComparisonCompletedAt, revision, updatedAt));
    }

    /** Returns the canonical fingerprint over every finding-projection column except itself. */
    static String projectionFingerprint(
            ObjectMapper objectMapper,
            String projectionId,
            String comparisonId,
            String authorityId,
            String status,
            Instant comparisonStartedAt,
            Instant comparisonCompletedAt,
            long sourceClassificationCount,
            String sourceClassificationRoot,
            long snapshotFindingCount,
            String snapshotRoot,
            String nextAfterObjectId,
            long nextPageSequence,
            long processedClassificationCount,
            long openedCount,
            long observedCount,
            long reopenedCount,
            long resolvedCount,
            long confirmedCount,
            String eventRoot,
            long revision,
            Instant startedAt,
            Instant completedAt,
            Instant updatedAt) {
        return ProtocolFingerprint.of(Objects.requireNonNull(objectMapper, "objectMapper"),
                new ProjectionMaterial(PROJECTION_SCHEMA, projectionId, comparisonId,
                        authorityId, status, comparisonStartedAt, comparisonCompletedAt,
                        sourceClassificationCount, sourceClassificationRoot, snapshotFindingCount,
                        snapshotRoot, nextAfterObjectId, nextPageSequence,
                        processedClassificationCount, openedCount, observedCount, reopenedCount,
                        resolvedCount, confirmedCount, eventRoot, revision, startedAt, completedAt,
                        updatedAt));
    }

    private record AuthorityMaterial(
            String schemaVersion,
            String authorityId,
            String activeProjectionId,
            String lastCompletedProjectionId,
            String lastAppliedComparisonId,
            Instant lastAppliedComparisonCompletedAt,
            long revision,
            Instant updatedAt) {
    }

    private record ProjectionMaterial(
            String schemaVersion,
            String projectionId,
            String comparisonId,
            String authorityId,
            String status,
            Instant comparisonStartedAt,
            Instant comparisonCompletedAt,
            long sourceClassificationCount,
            String sourceClassificationRoot,
            long snapshotFindingCount,
            String snapshotRoot,
            String nextAfterObjectId,
            long nextPageSequence,
            long processedClassificationCount,
            long openedCount,
            long observedCount,
            long reopenedCount,
            long resolvedCount,
            long confirmedCount,
            String eventRoot,
            long revision,
            Instant startedAt,
            Instant completedAt,
            Instant updatedAt) {
    }
}
