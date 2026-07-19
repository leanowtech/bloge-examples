package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservation;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerEntry;

import java.time.Instant;
import java.util.Objects;

/** Canonical whole-record fingerprint boundary for one observation ledger entry. */
public final class TestSuiteStabilityObservationLedgerEntryIntegrity {
    private TestSuiteStabilityObservationLedgerEntryIntegrity() {
    }

    /**
     * Recomputes the canonical entry fingerprint while excluding the fingerprint field itself.
     *
     * @param objectMapper canonical protocol mapper
     * @param entry complete ledger entry
     * @return lowercase SHA-256 protocol fingerprint
     */
    public static String fingerprint(
            ObjectMapper objectMapper,
            TestSuiteStabilityObservationLedgerEntry entry) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(entry, "entry");
        return ProtocolFingerprint.of(objectMapper, new Material(
                entry.schemaVersion(), entry.scopeFingerprint(), entry.sequence(),
                entry.previousObservationId(), entry.observation(), entry.appendedAt()));
    }

    /** @return whether the embedded fingerprint matches every entry field */
    public static boolean valid(
            ObjectMapper objectMapper,
            TestSuiteStabilityObservationLedgerEntry entry) {
        try {
            return entry != null && entry.entryFingerprint().equals(
                    fingerprint(objectMapper, entry));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private record Material(
            String schemaVersion,
            String scopeFingerprint,
            long sequence,
            String previousObservationId,
            TestSuiteStabilityObservation observation,
            Instant appendedAt) {
    }
}
