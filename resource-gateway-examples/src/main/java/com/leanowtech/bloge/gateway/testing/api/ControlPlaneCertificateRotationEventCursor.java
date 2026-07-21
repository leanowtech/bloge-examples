package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Durable two-phase cursor for one deployment scope and stable serving-slot identity.
 *
 * <p>{@link #stage(ControlPlaneCertificateRotationEventPage)} pins an exact next page before any
 * event is applied. {@link #commit(String)} advances only that staged page after all contained
 * events have reached durable rotation floors. A crash between those operations leaves an exact
 * replayable stage and never converts partial application into acknowledged delivery.</p>
 */
public interface ControlPlaneCertificateRotationEventCursor {

    /** Result of pinning an authenticated, fingerprint-verified next page. */
    enum StageStatus {
        /** A contiguous page was durably pinned for application. */
        STAGED,
        /** The exact in-flight page was already pinned. */
        REPLAYED,
        /** The page is the exact already committed head. */
        ALREADY_COMMITTED,
        /** Sequence, predecessor, or in-flight page identity conflicts. */
        CONFLICT
    }

    /** Result of advancing a previously staged page. */
    enum CommitStatus {
        /** The staged page became the durable committed head. */
        COMMITTED,
        /** The exact page was already committed. */
        REPLAYED,
        /** No exact staged page authorizes the requested commit. */
        CONFLICT
    }

    /**
     * Pins one exact contiguous page without advancing the committed cursor.
     *
     * @param page authenticated and fingerprint-verified source page
     * @return bounded staging outcome
     */
    StageResult stage(ControlPlaneCertificateRotationEventPage page);

    /**
     * Commits only the exact page previously returned as staged or replayed.
     *
     * @param pageFingerprint canonical staged page fingerprint
     * @return bounded commit outcome
     */
    CommitResult commit(String pageFingerprint);

    /** @return current tamper-checked committed and staged cursor state */
    Snapshot snapshot();

    /** @return true only for a cross-restart authority */
    boolean durable();

    /**
     * Immutable cursor state safe for health and protocol projection.
     *
     * @param schemaVersion cursor snapshot protocol version
     * @param deploymentScopeId exact event deployment scope
     * @param instanceId stable serving slot, never a process-start id
     * @param baselineSequence deployment-pinned cursor baseline sequence
     * @param baselinePageFingerprint deployment-pinned baseline page fingerprint
     * @param committedSequence current committed page sequence
     * @param committedPageFingerprint current committed page fingerprint
     * @param stagedSequence in-flight next sequence, or zero
     * @param stagedPreviousPageFingerprint staged predecessor fingerprint, or blank
     * @param stagedPageFingerprint staged page fingerprint, or blank
     */
    record Snapshot(
            String schemaVersion,
            String deploymentScopeId,
            String instanceId,
            long baselineSequence,
            String baselinePageFingerprint,
            long committedSequence,
            String committedPageFingerprint,
            long stagedSequence,
            String stagedPreviousPageFingerprint,
            String stagedPageFingerprint) {

        /** Current durable cursor snapshot protocol version. */
        public static final String SCHEMA_VERSION =
                "bloge.controlPlaneCertificateRotationEventCursorSnapshot.v1";
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
        private static final Pattern FINGERPRINT =
                Pattern.compile("sha256:[a-f0-9]{64}");

        /** Rejects contradictory baseline, committed, and staged cursor projections. */
        public Snapshot {
            schemaVersion = normalized(schemaVersion);
            deploymentScopeId = normalized(deploymentScopeId);
            instanceId = normalized(instanceId);
            baselinePageFingerprint = normalized(baselinePageFingerprint);
            committedPageFingerprint = normalized(committedPageFingerprint);
            stagedPreviousPageFingerprint = normalized(stagedPreviousPageFingerprint);
            stagedPageFingerprint = normalized(stagedPageFingerprint);
            boolean staged = stagedSequence > 0;
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !IDENTIFIER.matcher(deploymentScopeId).matches()
                    || !IDENTIFIER.matcher(instanceId).matches()
                    || baselineSequence < 0 || committedSequence < baselineSequence
                    || !FINGERPRINT.matcher(baselinePageFingerprint).matches()
                    || !FINGERPRINT.matcher(committedPageFingerprint).matches()
                    || staged && (stagedSequence != committedSequence + 1
                    || !committedPageFingerprint.equals(stagedPreviousPageFingerprint)
                    || !FINGERPRINT.matcher(stagedPageFingerprint).matches())
                    || !staged && (stagedSequence != 0
                    || !stagedPreviousPageFingerprint.isBlank()
                    || !stagedPageFingerprint.isBlank())) {
                throw new IllegalArgumentException(
                        "Control-plane certificate rotation event cursor snapshot is invalid");
            }
        }

        /** @return whether one exact page is awaiting complete local application */
        public boolean hasStagedPage() {
            return stagedSequence > 0;
        }
    }

    /**
     * Bounded page-staging result.
     *
     * @param status closed staging outcome
     * @param snapshot post-operation durable state
     */
    record StageResult(StageStatus status, Snapshot snapshot) {
        /** Requires a non-null outcome and state projection. */
        public StageResult {
            status = Objects.requireNonNull(status, "status");
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    /**
     * Bounded page-commit result.
     *
     * @param status closed commit outcome
     * @param snapshot post-operation durable state
     */
    record CommitResult(CommitStatus status, Snapshot snapshot) {
        /** Requires a non-null outcome and state projection. */
        public CommitResult {
            status = Objects.requireNonNull(status, "status");
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
