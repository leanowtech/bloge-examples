package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Database-snapshot page proving an ordered portion of one observation-ledger floor lifecycle.
 *
 * <p>{@code startingFloor} is the state after the request cursor. Applying every ordered signed
 * retirement yields {@code terminalFloor}. The page also pins the snapshot's complete
 * {@code currentFloor} and {@code head}; therefore a consumer can verify multiple pages without
 * accepting an append or retirement race. Empty pages are valid only at the current generation.</p>
 *
 * @param schemaVersion exact lifecycle page generation
 * @param requestFingerprint canonical request identity
 * @param request complete exact-suite cursor request
 * @param scopeFingerprint payload-free tenant, environment, and exact-suite scope
 * @param startingFloor floor represented by the exclusive generation cursor
 * @param retirements contiguous ordered signed retirement records in this page
 * @param terminalFloor floor derived after applying this page
 * @param currentFloor snapshot's current committed floor
 * @param head snapshot's current committed ledger head
 * @param hasMore whether another pinned retirement page is required
 * @param observedAt database snapshot time
 * @param pageFingerprint canonical fingerprint of every preceding field
 */
public record TestSuiteStabilityObservationLedgerLifecyclePage(
        String schemaVersion,
        String requestFingerprint,
        TestSuiteStabilityObservationLedgerLifecyclePageRequest request,
        String scopeFingerprint,
        TestSuiteStabilityObservationLedgerFloor startingFloor,
        List<TestSuiteStabilityObservationFloorRetirement> retirements,
        TestSuiteStabilityObservationLedgerFloor terminalFloor,
        TestSuiteStabilityObservationLedgerFloor currentFloor,
        TestSuiteStabilityObservationLedgerHead head,
        boolean hasMore,
        Instant observedAt,
        String pageFingerprint
) {
    /** Current lifecycle-page wire generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityObservationLedgerLifecyclePage.v1";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Freezes one bounded syntactically complete lifecycle page. */
    public TestSuiteStabilityObservationLedgerLifecyclePage {
        schemaVersion = normalized(schemaVersion);
        requestFingerprint = normalized(requestFingerprint);
        scopeFingerprint = normalized(scopeFingerprint);
        pageFingerprint = normalized(pageFingerprint);
        retirements = retirements == null ? List.of() : List.copyOf(retirements);
        if (!SCHEMA_VERSION.equals(schemaVersion) || !fingerprint(requestFingerprint)
                || request == null || !fingerprint(scopeFingerprint)
                || startingFloor == null || terminalFloor == null || currentFloor == null
                || head == null || retirements.size() > request.maximumRetirements()
                || observedAt == null || Instant.EPOCH.equals(observedAt)
                || !fingerprint(pageFingerprint)) {
            throw new IllegalArgumentException(
                    "Complete bounded observation-ledger lifecycle page is required");
        }
    }

    private static boolean fingerprint(String value) {
        return FINGERPRINT.matcher(normalized(value)).matches();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
