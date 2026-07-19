package com.leanowtech.bloge.gateway.testing.domain;

import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationArchiveSegment;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerFloor;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerHead;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Payload-free signed intent for one exact compact-observation floor retirement.
 *
 * <p>The evidence embeds the exact previous floor, pinned committed head, complete local archive
 * segment, bounded policy, and producer database time. A commit must re-read and compare all of
 * these values under the exact-suite lock before it may archive or delete an active row.</p>
 *
 * @param schemaVersion exact evidence generation
 * @param retirementId deterministic retirement identity
 * @param scopeFingerprint payload-free exact-suite scope
 * @param suiteRef exact immutable suite revision
 * @param retirementGeneration successor floor generation
 * @param previousFloor exact current floor used to prepare this intent
 * @param pinnedHead exact committed head used to bound the retirement
 * @param archiveSegment complete retired prefix and immediate successor
 * @param cutoffExclusive entries at or after this append-time boundary are not eligible
 * @param minimumRetainedEntries minimum active suffix retained after commit
 * @param maximumRetiredEntries caller-precommitted atomic retirement budget
 * @param retentionPolicyFingerprint immutable external retention-policy identity
 * @param reason closed reason for moving the floor
 * @param retiredAt producer database time at which the candidate was prepared
 */
public record TestSuiteStabilityObservationFloorRetirementEvidence(
        String schemaVersion,
        String retirementId,
        String scopeFingerprint,
        TestSuiteExecutionRequest.SuiteRef suiteRef,
        long retirementGeneration,
        TestSuiteStabilityObservationLedgerFloor previousFloor,
        TestSuiteStabilityObservationLedgerHead pinnedHead,
        TestSuiteStabilityObservationArchiveSegment archiveSegment,
        Instant cutoffExclusive,
        int minimumRetainedEntries,
        int maximumRetiredEntries,
        String retentionPolicyFingerprint,
        Reason reason,
        Instant retiredAt
) {
    /** Current signed floor-retirement evidence generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityObservationFloorRetirementEvidence.v1";
    /** Largest active suffix requirement accepted by one request. */
    public static final int MAXIMUM_RETAINED_ENTRIES = 1_000_000;
    private static final Pattern RETIREMENT_ID =
            Pattern.compile("stability-observation-retirement-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Freezes and validates one exact floor/head/archive/policy closure. */
    public TestSuiteStabilityObservationFloorRetirementEvidence {
        schemaVersion = normalized(schemaVersion);
        retirementId = normalized(retirementId);
        scopeFingerprint = normalized(scopeFingerprint);
        retentionPolicyFingerprint = normalized(retentionPolicyFingerprint);
        boolean sameScope = previousFloor != null && pinnedHead != null
                && archiveSegment != null && suiteRef != null
                && scopeFingerprint.equals(previousFloor.scopeFingerprint())
                && scopeFingerprint.equals(pinnedHead.scopeFingerprint())
                && scopeFingerprint.equals(archiveSegment.scopeFingerprint())
                && suiteRef.equals(previousFloor.suiteRef())
                && suiteRef.equals(pinnedHead.suiteRef())
                && suiteRef.equals(archiveSegment.suiteRef());
        boolean policy = cutoffExclusive != null && retiredAt != null
                && !cutoffExclusive.isAfter(retiredAt)
                && minimumRetainedEntries >= 1
                && minimumRetainedEntries <= MAXIMUM_RETAINED_ENTRIES
                && maximumRetiredEntries >= 1
                && maximumRetiredEntries
                <= TestSuiteStabilityObservationArchiveSegment.MAXIMUM_ENTRIES
                && archiveSegment != null
                && archiveSegment.retiredEntries().size() <= maximumRetiredEntries
                && fingerprint(retentionPolicyFingerprint)
                && reason == Reason.RETENTION_POLICY;
        boolean chain = sameScope
                && retirementGeneration == previousFloor.retirementGeneration() + 1
                && retirementGeneration == archiveSegment.retirementGeneration()
                && archiveSegment.fromSequence() == previousFloor.floorSequence()
                && archiveSegment.previousObservationId().equals(
                previousFloor.previousObservationId())
                && archiveSegment.previousEntryFingerprint().equals(
                previousFloor.previousEntryFingerprint())
                && archiveSegment.retiredEntries().getFirst().observation().evidence()
                .observationId().equals(previousFloor.floorObservationId())
                && archiveSegment.retiredEntries().getFirst().entryFingerprint()
                .equals(previousFloor.floorEntryFingerprint())
                && archiveSegment.successorEntry().sequence() <= pinnedHead.latestSequence()
                && pinnedHead.latestSequence() - archiveSegment.successorEntry().sequence() + 1
                >= minimumRetainedEntries
                && archiveSegment.retiredEntries().stream()
                .allMatch(entry -> entry.appendedAt().isBefore(cutoffExclusive))
                && retiredAt.equals(archiveSegment.archivedAt())
                && !retiredAt.isBefore(pinnedHead.updatedAt());
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !RETIREMENT_ID.matcher(retirementId).matches()
                || !fingerprint(scopeFingerprint) || !sameScope || !policy || !chain) {
            throw new IllegalArgumentException(
                    "Complete exact suite-stability floor retirement evidence is required");
        }
    }

    /** Closed reason set for irreversible active-ledger floor movement. */
    public enum Reason {
        /** A versioned retention policy selected an eligible contiguous prefix. */
        RETENTION_POLICY
    }

    private static boolean fingerprint(String value) {
        return FINGERPRINT.matcher(normalized(value)).matches();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
