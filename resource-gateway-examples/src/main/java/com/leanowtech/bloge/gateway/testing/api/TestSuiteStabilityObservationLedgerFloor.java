package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Database-authoritative first retained coordinate of one compact-observation ledger.
 *
 * <p>The floor is a separately fingerprinted CAS value. Generation zero represents the rollout
 * floor at sequence one. Every later generation must reference the exact signed retirement that
 * moved the floor, preserving the predecessor coordinate needed to verify the surviving chain even
 * after the active prefix has been archived and removed.</p>
 *
 * @param schemaVersion exact floor generation
 * @param scopeFingerprint payload-free tenant, environment, and exact-suite scope
 * @param suiteRef exact immutable suite revision
 * @param floorSequence first retained sequence
 * @param previousObservationId last archived predecessor; blank only at rollout
 * @param previousEntryFingerprint last archived entry identity; blank only at rollout
 * @param floorObservationId first retained observation identity
 * @param floorEntryFingerprint first retained entry identity
 * @param coverageFrom database append time of the first retained entry
 * @param retirementGeneration number of committed floor retirements
 * @param latestRetirementId latest signed retirement identity; blank at rollout
 * @param latestRetirementFingerprint latest complete retirement identity; blank at rollout
 * @param updatedAt database retirement time; rollout append time at generation zero
 * @param floorFingerprint canonical fingerprint of every preceding field
 */
public record TestSuiteStabilityObservationLedgerFloor(
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
        Instant updatedAt,
        String floorFingerprint
) {
    /** Current durable floor generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityObservationLedgerFloor.v1";
    private static final Pattern OBSERVATION_ID =
            Pattern.compile("stability-observation-[a-f0-9]{64}");
    private static final Pattern RETIREMENT_ID =
            Pattern.compile("stability-observation-retirement-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Freezes one rollout or signed-retirement floor CAS value. */
    public TestSuiteStabilityObservationLedgerFloor {
        schemaVersion = normalized(schemaVersion);
        scopeFingerprint = normalized(scopeFingerprint);
        previousObservationId = normalized(previousObservationId);
        previousEntryFingerprint = normalized(previousEntryFingerprint);
        floorObservationId = normalized(floorObservationId);
        floorEntryFingerprint = normalized(floorEntryFingerprint);
        latestRetirementId = normalized(latestRetirementId);
        latestRetirementFingerprint = normalized(latestRetirementFingerprint);
        floorFingerprint = normalized(floorFingerprint);
        boolean rollout = retirementGeneration == 0 && floorSequence == 1
                && previousObservationId.isBlank() && previousEntryFingerprint.isBlank()
                && latestRetirementId.isBlank() && latestRetirementFingerprint.isBlank();
        boolean retired = retirementGeneration > 0 && floorSequence > 1
                && observationId(previousObservationId)
                && fingerprint(previousEntryFingerprint)
                && RETIREMENT_ID.matcher(latestRetirementId).matches()
                && fingerprint(latestRetirementFingerprint);
        if (!SCHEMA_VERSION.equals(schemaVersion) || !fingerprint(scopeFingerprint)
                || suiteRef == null || floorSequence < 1 || (!rollout && !retired)
                || !observationId(floorObservationId) || !fingerprint(floorEntryFingerprint)
                || coverageFrom == null || Instant.EPOCH.equals(coverageFrom)
                || updatedAt == null || updatedAt.isBefore(coverageFrom)
                || !fingerprint(floorFingerprint)) {
            throw new IllegalArgumentException(
                    "Complete suite-stability observation ledger floor is required");
        }
    }

    private static boolean observationId(String value) {
        return OBSERVATION_ID.matcher(normalized(value)).matches();
    }

    private static boolean fingerprint(String value) {
        return FINGERPRINT.matcher(normalized(value)).matches();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
