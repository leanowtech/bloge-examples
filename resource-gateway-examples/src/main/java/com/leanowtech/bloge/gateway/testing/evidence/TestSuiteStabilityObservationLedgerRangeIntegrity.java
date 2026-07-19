package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerEntry;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerHead;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerRange;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Canonical whole-record fingerprint boundary for producer observation-range snapshots. */
public final class TestSuiteStabilityObservationLedgerRangeIntegrity {
    private TestSuiteStabilityObservationLedgerRangeIntegrity() {
    }

    /**
     * Recomputes the canonical range fingerprint while excluding the fingerprint field itself.
     *
     * @param objectMapper canonical protocol mapper
     * @param range complete range snapshot
     * @return lowercase SHA-256 protocol fingerprint
     */
    public static String fingerprint(
            ObjectMapper objectMapper,
            TestSuiteStabilityObservationLedgerRange range) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(range, "range");
        return ProtocolFingerprint.of(objectMapper, new Material(
                range.schemaVersion(), range.scopeFingerprint(), range.suiteRef(),
                range.floorSequence(), range.floorPreviousObservationId(),
                range.floorPreviousEntryFingerprint(), range.floorObservationId(),
                range.floorEntryFingerprint(), range.head(), range.afterSequence(),
                range.previousObservationId(), range.previousEntryFingerprint(),
                range.entries(), range.hasMore(), range.observedAt()));
    }

    /**
     * Verifies the complete producer range fingerprint.
     *
     * @param objectMapper canonical protocol mapper
     * @param range complete range snapshot
     * @return whether the embedded fingerprint matches every range field
     */
    public static boolean valid(
            ObjectMapper objectMapper,
            TestSuiteStabilityObservationLedgerRange range) {
        try {
            return range != null && range.rangeFingerprint().equals(
                    fingerprint(objectMapper, range));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private record Material(
            String schemaVersion,
            String scopeFingerprint,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            long floorSequence,
            String floorPreviousObservationId,
            String floorPreviousEntryFingerprint,
            String floorObservationId,
            String floorEntryFingerprint,
            TestSuiteStabilityObservationLedgerHead head,
            long afterSequence,
            String previousObservationId,
            String previousEntryFingerprint,
            List<TestSuiteStabilityObservationLedgerEntry> entries,
            boolean hasMore,
            Instant observedAt) {
    }
}
