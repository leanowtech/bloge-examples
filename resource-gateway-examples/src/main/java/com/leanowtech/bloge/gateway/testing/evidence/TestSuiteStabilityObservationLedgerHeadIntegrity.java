package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerHead;

import java.time.Instant;
import java.util.Objects;

/** Canonical whole-record fingerprint boundary for an observation ledger head. */
public final class TestSuiteStabilityObservationLedgerHeadIntegrity {
    private TestSuiteStabilityObservationLedgerHeadIntegrity() {
    }

    /**
     * Recomputes the canonical head fingerprint while excluding the fingerprint field itself.
     *
     * @param objectMapper canonical protocol mapper
     * @param head complete ledger head
     * @return lowercase SHA-256 protocol fingerprint
     */
    public static String fingerprint(
            ObjectMapper objectMapper,
            TestSuiteStabilityObservationLedgerHead head) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(head, "head");
        return ProtocolFingerprint.of(objectMapper, new Material(
                head.schemaVersion(), head.scopeFingerprint(), head.suiteRef(),
                head.coverageFrom(), head.latestSequence(), head.latestObservationId(),
                head.latestEntryFingerprint(), head.updatedAt()));
    }

    /** @return whether the embedded fingerprint matches every head field */
    public static boolean valid(
            ObjectMapper objectMapper,
            TestSuiteStabilityObservationLedgerHead head) {
        try {
            return head != null && head.headFingerprint().equals(
                    fingerprint(objectMapper, head));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private record Material(
            String schemaVersion,
            String scopeFingerprint,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            Instant coverageFrom,
            long latestSequence,
            String latestObservationId,
            String latestEntryFingerprint,
            Instant updatedAt) {
    }
}
