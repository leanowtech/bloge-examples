package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerFloor;

import java.time.Instant;
import java.util.Objects;

/** Canonical whole-record fingerprint boundary for an observation ledger floor. */
public final class TestSuiteStabilityObservationLedgerFloorIntegrity {
    private TestSuiteStabilityObservationLedgerFloorIntegrity() {
    }

    /**
     * Recomputes the canonical floor fingerprint while excluding the fingerprint field itself.
     *
     * @param objectMapper canonical protocol mapper
     * @param floor complete ledger floor
     * @return lowercase SHA-256 protocol fingerprint
     */
    public static String fingerprint(
            ObjectMapper objectMapper,
            TestSuiteStabilityObservationLedgerFloor floor) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(floor, "floor");
        return ProtocolFingerprint.of(objectMapper, new Material(
                floor.schemaVersion(), floor.scopeFingerprint(), floor.suiteRef(),
                floor.floorSequence(), floor.previousObservationId(),
                floor.previousEntryFingerprint(), floor.floorObservationId(),
                floor.floorEntryFingerprint(), floor.coverageFrom(),
                floor.retirementGeneration(), floor.latestRetirementId(),
                floor.latestRetirementFingerprint(), floor.updatedAt()));
    }

    /**
     * Checks the embedded whole-record fingerprint.
     *
     * @param objectMapper canonical protocol mapper
     * @param floor candidate floor
     * @return whether every field matches the embedded fingerprint
     */
    public static boolean valid(
            ObjectMapper objectMapper,
            TestSuiteStabilityObservationLedgerFloor floor) {
        try {
            return floor != null && floor.floorFingerprint().equals(
                    fingerprint(objectMapper, floor));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private record Material(
            String schemaVersion,
            String scopeFingerprint,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            long floorSequence,
            String previousObservationId,
            String previousEntryFingerprint,
            String floorObservationId,
            String floorEntryFingerprint,
            Instant coverageFrom,
            long retirementGeneration,
            String latestRetirementId,
            String latestRetirementFingerprint,
            Instant updatedAt) {
    }
}
