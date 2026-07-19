package com.leanowtech.bloge.gateway.testing.api;

import java.util.regex.Pattern;

/**
 * Bounded cursor request for one exact-suite observation-ledger lifecycle page.
 *
 * <p>The first page always starts after retirement generation zero and carries no caller pins.
 * Every continuation must carry the current-floor and head fingerprints returned by the first
 * page. This prevents a consumer from joining retirement pages from different snapshots while
 * still allowing it to prove the chain from the rollout floor.</p>
 *
 * @param schemaVersion exact lifecycle request generation
 * @param suiteRef exact immutable suite revision
 * @param afterRetirementGeneration exclusive retirement-generation cursor
 * @param maximumRetirements positive bounded page size
 * @param expectedCurrentFloorFingerprint snapshot floor pin; blank only for the first page
 * @param expectedHeadFingerprint snapshot head pin; blank only for the first page
 */
public record TestSuiteStabilityObservationLedgerLifecyclePageRequest(
        String schemaVersion,
        TestSuiteExecutionRequest.SuiteRef suiteRef,
        long afterRetirementGeneration,
        int maximumRetirements,
        String expectedCurrentFloorFingerprint,
        String expectedHeadFingerprint
) {
    /** Current lifecycle-page request generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityObservationLedgerLifecyclePageRequest.v1";
    /** Largest retirement page accepted by the public preview. */
    public static final int MAXIMUM_RETIREMENTS = 10;
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates one first-page or fully pinned continuation request. */
    public TestSuiteStabilityObservationLedgerLifecyclePageRequest {
        schemaVersion = normalized(schemaVersion);
        expectedCurrentFloorFingerprint = normalized(expectedCurrentFloorFingerprint);
        expectedHeadFingerprint = normalized(expectedHeadFingerprint);
        boolean firstPage = afterRetirementGeneration == 0
                && expectedCurrentFloorFingerprint.isBlank()
                && expectedHeadFingerprint.isBlank();
        boolean continuation = afterRetirementGeneration > 0
                && fingerprint(expectedCurrentFloorFingerprint)
                && fingerprint(expectedHeadFingerprint);
        if (!SCHEMA_VERSION.equals(schemaVersion) || suiteRef == null
                || normalized(suiteRef.suiteId()).isBlank() || suiteRef.revision() < 1
                || !fingerprint(suiteRef.fingerprint())
                || maximumRetirements < 1
                || maximumRetirements > MAXIMUM_RETIREMENTS
                || (!firstPage && !continuation)) {
            throw new IllegalArgumentException(
                    "Complete bounded lifecycle-page request is required");
        }
    }

    private static boolean fingerprint(String value) {
        return FINGERPRINT.matcher(normalized(value)).matches();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
