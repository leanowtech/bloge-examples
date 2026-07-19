package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Database-ordered append-only envelope around one signed compact observation.
 *
 * <p>Sequence, predecessor, and append time are database-authoritative facts. The observation is
 * independently signed; a later trend attestation must additionally close the exact ledger range
 * before these ordering facts may leave the producer trust boundary.</p>
 *
 * @param schemaVersion exact ledger envelope generation
 * @param scopeFingerprint payload-free tenant, environment, and exact-suite scope
 * @param sequence contiguous one-based append sequence inside the exact suite scope
 * @param previousObservationId predecessor identity; blank only for sequence one
 * @param observation signed compact source observation
 * @param appendedAt database append time
 * @param entryFingerprint canonical complete-entry fingerprint excluding itself
 */
public record TestSuiteStabilityObservationLedgerEntry(
        String schemaVersion,
        String scopeFingerprint,
        long sequence,
        String previousObservationId,
        TestSuiteStabilityObservation observation,
        Instant appendedAt,
        String entryFingerprint
) {
    /** Current internal durable ledger envelope generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityObservationLedgerEntry.v1";
    private static final Pattern OBSERVATION_ID =
            Pattern.compile("stability-observation-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates contiguous-chain shape and complete signed observation material. */
    public TestSuiteStabilityObservationLedgerEntry {
        schemaVersion = normalized(schemaVersion);
        scopeFingerprint = normalized(scopeFingerprint);
        previousObservationId = normalized(previousObservationId);
        entryFingerprint = normalized(entryFingerprint);
        boolean predecessorValid = sequence == 1
                ? previousObservationId.isBlank()
                : OBSERVATION_ID.matcher(previousObservationId).matches();
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !FINGERPRINT.matcher(scopeFingerprint).matches()
                || sequence < 1 || !predecessorValid || observation == null
                || appendedAt == null || Instant.EPOCH.equals(appendedAt)
                || !FINGERPRINT.matcher(entryFingerprint).matches()) {
            throw new IllegalArgumentException(
                    "Complete suite-stability observation ledger entry is required");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
