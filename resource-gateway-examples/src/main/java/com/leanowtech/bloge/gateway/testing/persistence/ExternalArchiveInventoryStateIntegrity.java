package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Objects;

/**
 * Canonical whole-record fingerprints for durable external inventory control state.
 *
 * <p>The inventory collector, downstream comparison, operational health, and source-retention
 * authority must agree on one byte-level state definition. Keeping that definition here prevents a
 * consumer from accidentally verifying only the convenient subset of an authority or cycle row.
 * Shape and lifecycle validation remain owned by each consuming control plane; this class defines
 * only the versioned fingerprint material.</p>
 */
final class ExternalArchiveInventoryStateIntegrity {
    private static final String AUTHORITY_SCHEMA =
            "bloge.testSuiteStabilityObservationExternalInventoryAuthorityState.v1";
    private static final String CYCLE_SCHEMA =
            "bloge.testSuiteStabilityObservationExternalInventoryCycleState.v1";

    private ExternalArchiveInventoryStateIntegrity() {
    }

    /** Returns the canonical fingerprint for every persisted authority-state column. */
    static String authorityFingerprint(
            ObjectMapper objectMapper,
            String authorityId,
            String leaseOwner,
            String leaseToken,
            long leaseEpoch,
            Instant leaseUntil,
            long revision,
            String activeCycleId,
            String lastCompletedCycleId,
            Instant lastSuccessAt,
            Instant updatedAt) {
        return ProtocolFingerprint.of(Objects.requireNonNull(objectMapper, "objectMapper"),
                new AuthorityMaterial(AUTHORITY_SCHEMA, authorityId, leaseOwner, leaseToken,
                        leaseEpoch, leaseUntil, revision, activeCycleId, lastCompletedCycleId,
                        lastSuccessAt, updatedAt));
    }

    /** Returns the canonical fingerprint for every persisted inventory-cycle column. */
    static String cycleFingerprint(
            ObjectMapper objectMapper,
            String cycleId,
            String authorityId,
            String status,
            String trustDomain,
            String archiveSetId,
            String failureDomain,
            String snapshotId,
            Instant snapshotAt,
            long snapshotObjectCount,
            String snapshotRoot,
            String nextAfterObjectId,
            long nextPageSequence,
            long accumulatedObjectCount,
            String accumulatedRoot,
            String lastObjectId,
            long revision,
            Instant startedAt,
            Instant completedAt,
            Instant updatedAt) {
        return ProtocolFingerprint.of(Objects.requireNonNull(objectMapper, "objectMapper"),
                new CycleMaterial(CYCLE_SCHEMA, cycleId, authorityId, status, trustDomain,
                        archiveSetId, failureDomain, snapshotId, snapshotAt, snapshotObjectCount,
                        snapshotRoot, nextAfterObjectId, nextPageSequence,
                        accumulatedObjectCount, accumulatedRoot, lastObjectId, revision,
                        startedAt, completedAt, updatedAt));
    }

    private record AuthorityMaterial(
            String schemaVersion,
            String authorityId,
            String leaseOwner,
            String leaseToken,
            long leaseEpoch,
            Instant leaseUntil,
            long revision,
            String activeCycleId,
            String lastCompletedCycleId,
            Instant lastSuccessAt,
            Instant updatedAt) {
    }

    private record CycleMaterial(
            String schemaVersion,
            String cycleId,
            String authorityId,
            String status,
            String trustDomain,
            String archiveSetId,
            String failureDomain,
            String snapshotId,
            Instant snapshotAt,
            long snapshotObjectCount,
            String snapshotRoot,
            String nextAfterObjectId,
            long nextPageSequence,
            long accumulatedObjectCount,
            String accumulatedRoot,
            String lastObjectId,
            long revision,
            Instant startedAt,
            Instant completedAt,
            Instant updatedAt) {
    }
}
