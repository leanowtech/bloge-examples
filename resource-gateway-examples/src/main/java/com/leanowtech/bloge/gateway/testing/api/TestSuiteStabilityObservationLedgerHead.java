package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Database-authoritative rollout floor and latest committed compact-observation coordinate.
 *
 * @param schemaVersion exact head generation
 * @param scopeFingerprint payload-free tenant, environment, and exact-suite scope
 * @param suiteRef exact immutable suite revision
 * @param coverageFrom first database append time covered by the ledger
 * @param latestSequence latest contiguous committed append sequence
 * @param latestObservationId latest signed observation identity
 * @param latestEntryFingerprint latest canonical ledger envelope identity
 * @param updatedAt database time of the latest append
 * @param headFingerprint canonical complete-head fingerprint excluding itself
 */
public record TestSuiteStabilityObservationLedgerHead(
        String schemaVersion,
        String scopeFingerprint,
        TestSuiteExecutionRequest.SuiteRef suiteRef,
        Instant coverageFrom,
        long latestSequence,
        String latestObservationId,
        String latestEntryFingerprint,
        Instant updatedAt,
        String headFingerprint
) {
    /** Current internal durable ledger-head generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityObservationLedgerHead.v1";
    private static final Pattern OBSERVATION_ID =
            Pattern.compile("stability-observation-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates one non-empty rollout floor and latest append coordinate. */
    public TestSuiteStabilityObservationLedgerHead {
        schemaVersion = normalized(schemaVersion);
        scopeFingerprint = normalized(scopeFingerprint);
        latestObservationId = normalized(latestObservationId);
        latestEntryFingerprint = normalized(latestEntryFingerprint);
        headFingerprint = normalized(headFingerprint);
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !FINGERPRINT.matcher(scopeFingerprint).matches()
                || suiteRef == null || normalized(suiteRef.suiteId()).isBlank()
                || suiteRef.revision() < 1
                || !FINGERPRINT.matcher(normalized(suiteRef.fingerprint())).matches()
                || coverageFrom == null || Instant.EPOCH.equals(coverageFrom)
                || latestSequence < 1
                || !OBSERVATION_ID.matcher(latestObservationId).matches()
                || !FINGERPRINT.matcher(latestEntryFingerprint).matches()
                || updatedAt == null || updatedAt.isBefore(coverageFrom)
                || !FINGERPRINT.matcher(headFingerprint).matches()) {
            throw new IllegalArgumentException(
                    "Complete suite-stability observation ledger head is required");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
