package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Content-addressed local archive segment prepared before an observation-ledger floor moves.
 *
 * <p>The segment contains a contiguous retired prefix and duplicates its immediate surviving
 * successor. The successor closes the predecessor chain without relying on active-table state.
 * This local archive is a transactional durability primitive, not an external WORM claim.</p>
 *
 * @param schemaVersion exact archive generation
 * @param segmentId deterministic archive identity
 * @param scopeFingerprint payload-free exact-suite scope
 * @param suiteRef exact immutable suite revision
 * @param retirementGeneration successor retirement generation
 * @param previousObservationId predecessor before the archived prefix; blank at rollout
 * @param previousEntryFingerprint predecessor entry identity; blank at rollout
 * @param retiredEntries non-empty contiguous prefix removed from the active ledger
 * @param successorEntry first entry retained after the archived prefix
 * @param archivedAt database time at which the candidate snapshot was prepared
 * @param segmentFingerprint canonical fingerprint of every preceding field
 */
public record TestSuiteStabilityObservationArchiveSegment(
        String schemaVersion,
        String segmentId,
        String scopeFingerprint,
        TestSuiteExecutionRequest.SuiteRef suiteRef,
        long retirementGeneration,
        String previousObservationId,
        String previousEntryFingerprint,
        List<TestSuiteStabilityObservationLedgerEntry> retiredEntries,
        TestSuiteStabilityObservationLedgerEntry successorEntry,
        Instant archivedAt,
        String segmentFingerprint
) {
    /** Current local archive segment generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityObservationArchiveSegment.v1";
    /** Maximum number of entries retired by one atomic transaction. */
    public static final int MAXIMUM_ENTRIES = 100;
    private static final Pattern SEGMENT_ID =
            Pattern.compile("stability-observation-archive-[a-f0-9]{64}");
    private static final Pattern OBSERVATION_ID =
            Pattern.compile("stability-observation-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Freezes and structurally closes one bounded contiguous archive segment. */
    public TestSuiteStabilityObservationArchiveSegment {
        schemaVersion = normalized(schemaVersion);
        segmentId = normalized(segmentId);
        scopeFingerprint = normalized(scopeFingerprint);
        previousObservationId = normalized(previousObservationId);
        previousEntryFingerprint = normalized(previousEntryFingerprint);
        retiredEntries = retiredEntries == null ? List.of() : List.copyOf(retiredEntries);
        segmentFingerprint = normalized(segmentFingerprint);
        boolean rollout = retirementGeneration == 1
                && previousObservationId.isBlank() && previousEntryFingerprint.isBlank();
        boolean continued = retirementGeneration > 1
                && observationId(previousObservationId)
                && fingerprint(previousEntryFingerprint);
        if (!SCHEMA_VERSION.equals(schemaVersion) || !SEGMENT_ID.matcher(segmentId).matches()
                || !fingerprint(scopeFingerprint) || suiteRef == null
                || retirementGeneration < 1 || (!rollout && !continued)
                || retiredEntries.isEmpty() || retiredEntries.size() > MAXIMUM_ENTRIES
                || successorEntry == null || archivedAt == null || Instant.EPOCH.equals(archivedAt)
                || !fingerprint(segmentFingerprint)
                || !closed(retiredEntries, successorEntry, scopeFingerprint,
                previousObservationId, archivedAt)) {
            throw new IllegalArgumentException(
                    "Complete contiguous suite-stability archive segment is required");
        }
    }

    /** @return first retired ledger sequence */
    public long fromSequence() {
        return retiredEntries.getFirst().sequence();
    }

    /** @return last retired ledger sequence */
    public long throughSequence() {
        return retiredEntries.getLast().sequence();
    }

    private static boolean closed(
            List<TestSuiteStabilityObservationLedgerEntry> entries,
            TestSuiteStabilityObservationLedgerEntry successor,
            String scopeFingerprint,
            String predecessor,
            Instant archivedAt) {
        long sequence = entries.getFirst().sequence();
        String previous = predecessor;
        for (TestSuiteStabilityObservationLedgerEntry entry : entries) {
            if (entry == null || entry.sequence() != sequence
                    || !scopeFingerprint.equals(entry.scopeFingerprint())
                    || !previous.equals(entry.previousObservationId())
                    || entry.appendedAt().isAfter(archivedAt)) {
                return false;
            }
            previous = entry.observation().evidence().observationId();
            sequence++;
        }
        return successor.sequence() == sequence
                && scopeFingerprint.equals(successor.scopeFingerprint())
                && previous.equals(successor.previousObservationId())
                && !successor.appendedAt().isAfter(archivedAt);
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
