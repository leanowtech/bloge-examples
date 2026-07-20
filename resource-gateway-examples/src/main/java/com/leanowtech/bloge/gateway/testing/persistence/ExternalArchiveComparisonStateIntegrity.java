package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Objects;

/** Shared canonical fingerprints and replay links for comparison storage. */
final class ExternalArchiveComparisonStateIntegrity {
    private static final String AUTHORITY_SCHEMA =
            "bloge.testSuiteStabilityObservationExternalComparisonAuthorityState.v1";
    private static final String COMPARISON_SCHEMA =
            "bloge.testSuiteStabilityObservationExternalArchiveComparisonState.v1";
    private static final String CLASSIFICATION_ROW_SCHEMA =
            "bloge.testSuiteStabilityObservationExternalClassificationRow.v1";
    private static final String TOPOLOGY_SCHEMA =
            "bloge.testSuiteStabilityObservationExternalArchiveTopology.v1";
    private static final String EXPECTED_ROOT_LINK_SCHEMA =
            "bloge.testSuiteStabilityObservationExternalArchiveExpectedRootLink.v1";
    private static final String CLASSIFICATION_ROOT_LINK_SCHEMA =
            "bloge.testSuiteStabilityObservationExternalArchiveClassificationRootLink.v1";

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

    /** Returns the canonical fingerprint over every comparison-state column except itself. */
    static String comparisonFingerprint(
            ObjectMapper objectMapper,
            String comparisonId,
            String cycleId,
            String authorityId,
            String status,
            String trustDomain,
            String archiveSetId,
            String failureDomain,
            String remoteSnapshotId,
            long remoteObjectCount,
            String remoteRoot,
            long expectedObjectCount,
            String expectedRoot,
            String nextAfterObjectId,
            long nextPageSequence,
            long classifiedObjectCount,
            long matchedCount,
            long missingRemoteCount,
            long unexpectedRemoteCount,
            long materialConflictCount,
            long retentionShortenedCount,
            long unknownCount,
            String classificationRoot,
            long revision,
            Instant startedAt,
            Instant completedAt,
            Instant updatedAt) {
        return ProtocolFingerprint.of(Objects.requireNonNull(objectMapper, "objectMapper"),
                new ComparisonMaterial(COMPARISON_SCHEMA, comparisonId, cycleId, authorityId,
                        status, trustDomain, archiveSetId, failureDomain, remoteSnapshotId,
                        remoteObjectCount, remoteRoot, expectedObjectCount, expectedRoot,
                        nextAfterObjectId, nextPageSequence, classifiedObjectCount, matchedCount,
                        missingRemoteCount, unexpectedRemoteCount, materialConflictCount,
                        retentionShortenedCount, unknownCount, classificationRoot, revision,
                        startedAt, completedAt, updatedAt));
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

    /** Returns the canonical topology binding used by frozen expected objects. */
    static String topologyFingerprint(
            ObjectMapper objectMapper,
            String trustDomain,
            String archiveSetId,
            String authorityId,
            String failureDomain) {
        return ProtocolFingerprint.of(Objects.requireNonNull(objectMapper, "objectMapper"),
                new TopologyMaterial(TOPOLOGY_SCHEMA, trustDomain, archiveSetId, authorityId,
                        failureDomain));
    }

    /** Appends one frozen expected object to its order-sensitive replay root. */
    static String appendExpectedRoot(
            ObjectMapper objectMapper,
            String previousRoot,
            String itemFingerprint,
            String topologyFingerprint) {
        return ProtocolFingerprint.of(Objects.requireNonNull(objectMapper, "objectMapper"),
                new ExpectedRootLink(EXPECTED_ROOT_LINK_SCHEMA, previousRoot, itemFingerprint,
                        topologyFingerprint));
    }

    /** Appends one classification to its order-sensitive replay root. */
    static String appendClassificationRoot(
            ObjectMapper objectMapper,
            String previousRoot,
            String classificationFingerprint) {
        return ProtocolFingerprint.of(Objects.requireNonNull(objectMapper, "objectMapper"),
                new ClassificationRootLink(CLASSIFICATION_ROOT_LINK_SCHEMA, previousRoot,
                        classificationFingerprint));
    }

    private record AuthorityMaterial(
            String schemaVersion,
            String authorityId,
            String activeComparisonId,
            String lastCompletedComparisonId,
            long revision,
            Instant updatedAt) {
    }

    private record ComparisonMaterial(
            String schemaVersion,
            String comparisonId,
            String cycleId,
            String authorityId,
            String status,
            String trustDomain,
            String archiveSetId,
            String failureDomain,
            String remoteSnapshotId,
            long remoteObjectCount,
            String remoteRoot,
            long expectedObjectCount,
            String expectedRoot,
            String nextAfterObjectId,
            long nextPageSequence,
            long classifiedObjectCount,
            long matchedCount,
            long missingRemoteCount,
            long unexpectedRemoteCount,
            long materialConflictCount,
            long retentionShortenedCount,
            long unknownCount,
            String classificationRoot,
            long revision,
            Instant startedAt,
            Instant completedAt,
            Instant updatedAt) {
    }

    private record ClassificationRowMaterial(
            String schemaVersion,
            DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                    .Classification classification,
            long pageSequence,
            Instant committedAt) {
    }

    private record TopologyMaterial(
            String schemaVersion,
            String trustDomain,
            String archiveSetId,
            String authorityId,
            String failureDomain) {
    }

    private record ExpectedRootLink(
            String schemaVersion,
            String previousRoot,
            String itemFingerprint,
            String topologyFingerprint) {
    }

    private record ClassificationRootLink(
            String schemaVersion,
            String previousRoot,
            String classificationFingerprint) {
    }
}
