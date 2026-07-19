package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Receipt-aware v2 snapshot page for one compact-observation floor lifecycle.
 *
 * <p>The page preserves the v1 retirement list and adds the exact external archive receipt set
 * committed for every retirement at the same index. Duplication of the retirement nested in each
 * challenge-bound archive request is intentional: consumers receive the exact write-admission
 * artifact rather than a producer-defined projection. Canonical integrity and transition closure
 * are established by the corresponding integrity service.</p>
 *
 * @param schemaVersion exact receipt-aware lifecycle page generation
 * @param requestFingerprint canonical v1 cursor-request identity
 * @param request complete exact-suite cursor request
 * @param scopeFingerprint payload-free tenant, environment, and exact-suite scope
 * @param startingFloor floor represented by the exclusive generation cursor
 * @param retirements contiguous ordered signed retirement records
 * @param externalArchiveReceiptSets exact receipt set paired with each retirement
 * @param terminalFloor floor derived after applying this page
 * @param currentFloor snapshot's current committed floor
 * @param head snapshot's current committed ledger head
 * @param hasMore whether another pinned archive-proof page is required
 * @param observedAt producer database snapshot time
 * @param pageFingerprint canonical fingerprint of every preceding field
 */
public record TestSuiteStabilityObservationLedgerLifecycleArchivePage(
        String schemaVersion,
        String requestFingerprint,
        TestSuiteStabilityObservationLedgerLifecyclePageRequest request,
        String scopeFingerprint,
        TestSuiteStabilityObservationLedgerFloor startingFloor,
        List<TestSuiteStabilityObservationFloorRetirement> retirements,
        List<TestSuiteStabilityObservationExternalArchiveReceiptSet>
                externalArchiveReceiptSets,
        TestSuiteStabilityObservationLedgerFloor terminalFloor,
        TestSuiteStabilityObservationLedgerFloor currentFloor,
        TestSuiteStabilityObservationLedgerHead head,
        boolean hasMore,
        Instant observedAt,
        String pageFingerprint
) {
    /** Current receipt-aware lifecycle-page wire generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityObservationLedgerLifecyclePage.v2";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Freezes one bounded page with pairwise exact retirement and receipt-set material. */
    public TestSuiteStabilityObservationLedgerLifecycleArchivePage {
        schemaVersion = normalized(schemaVersion);
        requestFingerprint = normalized(requestFingerprint);
        scopeFingerprint = normalized(scopeFingerprint);
        pageFingerprint = normalized(pageFingerprint);
        retirements = retirements == null ? List.of() : List.copyOf(retirements);
        externalArchiveReceiptSets = externalArchiveReceiptSets == null
                ? List.of() : List.copyOf(externalArchiveReceiptSets);
        if (!SCHEMA_VERSION.equals(schemaVersion) || !fingerprint(requestFingerprint)
                || request == null || !fingerprint(scopeFingerprint)
                || startingFloor == null || terminalFloor == null || currentFloor == null
                || head == null || retirements.size() > request.maximumRetirements()
                || retirements.size() != externalArchiveReceiptSets.size()
                || observedAt == null || Instant.EPOCH.equals(observedAt)
                || !fingerprint(pageFingerprint)) {
            throw new IllegalArgumentException(
                    "Complete bounded receipt-aware lifecycle page is required");
        }
        for (int index = 0; index < retirements.size(); index++) {
            TestSuiteStabilityObservationFloorRetirement retirement = retirements.get(index);
            TestSuiteStabilityObservationExternalArchiveReceiptSet receiptSet =
                    externalArchiveReceiptSets.get(index);
            if (retirement == null || receiptSet == null
                    || !retirement.equals(receiptSet.request().retirement())
                    || receiptSet.confirmedAt().isAfter(observedAt)) {
                throw new IllegalArgumentException(
                        "Lifecycle retirement and external receipt set are inconsistent");
            }
        }
    }

    private static boolean fingerprint(String value) {
        return FINGERPRINT.matcher(normalized(value)).matches();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
