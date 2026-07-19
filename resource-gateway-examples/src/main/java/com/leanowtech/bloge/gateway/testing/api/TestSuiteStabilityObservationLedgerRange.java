package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Producer-authoritative, fingerprinted snapshot of one compact-observation ledger page.
 *
 * <p>The range is read under the exact-suite ledger lock. It binds the retained floor, committed
 * head, exclusive request cursor, predecessor coordinate, and every returned entry. The enclosing
 * cross-retention trend attestation signs this range before it may leave the producer boundary.</p>
 *
 * @param schemaVersion exact range generation
 * @param scopeFingerprint payload-free tenant, environment, and exact-suite scope
 * @param suiteRef exact immutable suite revision
 * @param floorSequence first retained sequence
 * @param floorPreviousObservationId predecessor retired before the floor; blank at rollout
 * @param floorPreviousEntryFingerprint predecessor entry identity; blank at rollout
 * @param floorObservationId first retained observation identity
 * @param floorEntryFingerprint first retained entry identity
 * @param head committed head observed for this snapshot
 * @param afterSequence exclusive caller cursor
 * @param previousObservationId observation at {@code afterSequence}; floor predecessor at floor
 * @param previousEntryFingerprint entry at {@code afterSequence}; floor predecessor at floor
 * @param entries contiguous bounded page
 * @param hasMore whether the pinned head contains a successor after this page
 * @param observedAt database time at which the locked snapshot was completed
 * @param rangeFingerprint canonical fingerprint of every preceding field
 */
public record TestSuiteStabilityObservationLedgerRange(
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
        Instant observedAt,
        String rangeFingerprint
) {
    /** Current producer snapshot generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityObservationLedgerRange.v1";
    private static final Pattern OBSERVATION_ID =
            Pattern.compile("stability-observation-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Freezes the page and validates floor, cursor, chain, and head closure. */
    public TestSuiteStabilityObservationLedgerRange {
        schemaVersion = normalized(schemaVersion);
        scopeFingerprint = normalized(scopeFingerprint);
        floorPreviousObservationId = normalized(floorPreviousObservationId);
        floorPreviousEntryFingerprint = normalized(floorPreviousEntryFingerprint);
        floorObservationId = normalized(floorObservationId);
        floorEntryFingerprint = normalized(floorEntryFingerprint);
        previousObservationId = normalized(previousObservationId);
        previousEntryFingerprint = normalized(previousEntryFingerprint);
        entries = entries == null ? List.of() : List.copyOf(entries);
        rangeFingerprint = normalized(rangeFingerprint);
        boolean rolloutFloor = floorSequence == 1
                && floorPreviousObservationId.isBlank()
                && floorPreviousEntryFingerprint.isBlank();
        boolean retiredFloor = floorSequence > 1
                && observationId(floorPreviousObservationId)
                && fingerprint(floorPreviousEntryFingerprint);
        boolean baseValid = SCHEMA_VERSION.equals(schemaVersion)
                && fingerprint(scopeFingerprint) && suiteRef != null
                && floorSequence >= 1 && (rolloutFloor || retiredFloor)
                && observationId(floorObservationId) && fingerprint(floorEntryFingerprint)
                && head != null && scopeFingerprint.equals(head.scopeFingerprint())
                && suiteRef.equals(head.suiteRef()) && floorSequence <= head.latestSequence()
                && afterSequence >= floorSequence - 1
                && afterSequence <= head.latestSequence()
                && entries.size() <= TestSuiteStabilityCrossRetentionTrendAnalysisRequest
                .MAXIMUM_RUNS
                && observedAt != null && !observedAt.isBefore(head.updatedAt())
                && fingerprint(rangeFingerprint);
        boolean predecessorValid = afterSequence == floorSequence - 1
                ? previousObservationId.equals(floorPreviousObservationId)
                && previousEntryFingerprint.equals(floorPreviousEntryFingerprint)
                : observationId(previousObservationId) && fingerprint(previousEntryFingerprint);
        boolean entriesValid = entriesValid(entries, scopeFingerprint, afterSequence,
                previousObservationId, observedAt);
        long lastSequence = entries.isEmpty() ? afterSequence : entries.getLast().sequence();
        boolean pageValid = entries.isEmpty()
                ? afterSequence == head.latestSequence() && !hasMore
                : hasMore == (lastSequence < head.latestSequence());
        boolean floorVisible = visibleEntryMatches(
                entries, floorSequence, floorObservationId, floorEntryFingerprint);
        boolean headVisible = visibleEntryMatches(
                entries, head.latestSequence(), head.latestObservationId(),
                head.latestEntryFingerprint());
        if (!baseValid || !predecessorValid || !entriesValid || !pageValid
                || !floorVisible || !headVisible) {
            throw new IllegalArgumentException(
                    "Complete contiguous suite-stability observation range is required");
        }
    }

    private static boolean entriesValid(
            List<TestSuiteStabilityObservationLedgerEntry> entries,
            String scopeFingerprint,
            long afterSequence,
            String predecessor,
            Instant observedAt) {
        long expected = afterSequence + 1;
        String previous = predecessor;
        for (TestSuiteStabilityObservationLedgerEntry entry : entries) {
            if (entry == null || entry.sequence() != expected
                    || !scopeFingerprint.equals(entry.scopeFingerprint())
                    || !previous.equals(entry.previousObservationId())
                    || entry.appendedAt().isAfter(observedAt)) {
                return false;
            }
            previous = entry.observation().evidence().observationId();
            expected++;
        }
        return true;
    }

    private static boolean visibleEntryMatches(
            List<TestSuiteStabilityObservationLedgerEntry> entries,
            long sequence,
            String observationId,
            String entryFingerprint) {
        for (TestSuiteStabilityObservationLedgerEntry entry : entries) {
            if (entry.sequence() == sequence) {
                return observationId.equals(
                        entry.observation().evidence().observationId())
                        && entryFingerprint.equals(entry.entryFingerprint());
            }
        }
        return true;
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
