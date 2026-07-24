package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Objects;

/**
 * Canonical sealing and independent verification for failure-aware state write evidence.
 */
public final class MirrorStateWriteOutcomeRunEvidenceIntegrity {
    /** Maximum canonical state-evidence bytes admitted to hashing. */
    public static final int MAXIMUM_CANONICAL_BYTES =
            64 * 1024 * 1024;
    /** Maximum canonical material admitted for one failure fingerprint. */
    public static final int MAXIMUM_FAILURE_MATERIAL_BYTES =
            16 * 1024;

    private MirrorStateWriteOutcomeRunEvidenceIntegrity() {
    }

    /**
     * Seals one complete failure-aware state-evidence value.
     *
     * @param mapper canonical protocol mapper
     * @param evidence unsealed evidence with already sealed failure attempts
     * @return sealed immutable evidence
     */
    public static MirrorStateWriteOutcomeRunEvidence seal(
            ObjectMapper mapper,
            MirrorStateWriteOutcomeRunEvidence evidence) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(evidence, "evidence");
        verifyFailureFingerprints(mapper, evidence);
        MirrorStateWriteOutcomeRunEvidence material =
                evidence.withFingerprint("");
        String fingerprint = ProtocolFingerprint.ofBounded(
                mapper, material, MAXIMUM_CANONICAL_BYTES);
        MirrorStateWriteOutcomeRunEvidence sealed =
                material.withFingerprint(fingerprint);
        verify(mapper, sealed);
        return sealed;
    }

    /**
     * Recomputes the complete state-evidence and every nested failure fingerprint.
     *
     * @param mapper canonical protocol mapper
     * @param evidence sealed evidence
     */
    public static void verify(
            ObjectMapper mapper,
            MirrorStateWriteOutcomeRunEvidence evidence) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(evidence, "evidence");
        verifyFailureFingerprints(mapper, evidence);
        String expected = ProtocolFingerprint.ofBounded(
                mapper, evidence.withFingerprint(""),
                MAXIMUM_CANONICAL_BYTES);
        if (!expected.equals(
                evidence.stateEvidenceFingerprint())) {
            throw new IllegalArgumentException(
                    "mirror state write-outcome evidence fingerprint mismatch");
        }
    }

    /**
     * Derives the canonical payload-free identity of one failed write attempt.
     *
     * @param mapper canonical protocol mapper
     * @param writeEffectRef exact write effect
     * @param observedStateRef exact state head observed before the attempt
     * @param observedStateRevision observed committed revision
     * @param observedWorldFingerprint observed business-world fingerprint
     * @param observedLogicalClock observed deterministic time
     * @param requestFingerprint canonical invocation input identity
     * @param outcome conservative failure outcome
     * @param stage last trustworthy processing stage
     * @param disposition proven state-head effect
     * @param retryable whether another delegate attempt is authorized
     * @param errorCode stable failure code
     * @param errorType normalized failure family
     * @return canonical failure fingerprint
     */
    public static String failureFingerprint(
            ObjectMapper mapper,
            MirrorArtifactRef writeEffectRef,
            MirrorArtifactRef observedStateRef,
            long observedStateRevision,
            String observedWorldFingerprint,
            Instant observedLogicalClock,
            String requestFingerprint,
            MirrorStateWriteOutcomeRunEvidence.WriteOutcome outcome,
            MirrorStateWriteOutcomeRunEvidence.WriteStage stage,
            MirrorStateWriteOutcomeRunEvidence.StateDisposition disposition,
            boolean retryable,
            String errorCode,
            String errorType) {
        return ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                new FailureMaterial(
                        Objects.requireNonNull(
                                writeEffectRef,
                                "writeEffectRef"),
                        Objects.requireNonNull(
                                observedStateRef,
                                "observedStateRef"),
                        observedStateRevision,
                        Objects.requireNonNull(
                                observedWorldFingerprint,
                                "observedWorldFingerprint"),
                        Objects.requireNonNull(
                                observedLogicalClock,
                                "observedLogicalClock"),
                        Objects.requireNonNull(
                                requestFingerprint,
                                "requestFingerprint"),
                        Objects.requireNonNull(
                                outcome, "outcome"),
                        Objects.requireNonNull(
                                stage, "stage"),
                        Objects.requireNonNull(
                                disposition,
                                "disposition"),
                        retryable,
                        Objects.requireNonNull(
                                errorCode, "errorCode"),
                        Objects.requireNonNull(
                                errorType, "errorType")),
                MAXIMUM_FAILURE_MATERIAL_BYTES);
    }

    /**
     * Returns the exact payload-free artifact reference for sealed evidence.
     *
     * @param evidence verified state evidence
     * @return exact state-evidence reference
     */
    public static MirrorArtifactRef reference(
            MirrorStateWriteOutcomeRunEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        return new MirrorArtifactRef(
                "MIRROR_STATE_RUN_EVIDENCE",
                evidence.runId(), 3,
                evidence.stateEvidenceFingerprint());
    }

    private static void verifyFailureFingerprints(
            ObjectMapper mapper,
            MirrorStateWriteOutcomeRunEvidence evidence) {
        for (MirrorStateWriteOutcomeRunEvidence.StateWriteAttempt
                attempt : evidence.writeAttempts()) {
            if (attempt.outcome()
                    == MirrorStateWriteOutcomeRunEvidence.WriteOutcome
                    .COMMITTED
                    || attempt.outcome()
                    == MirrorStateWriteOutcomeRunEvidence.WriteOutcome
                    .REPLAYED) {
                continue;
            }
            String expected = failureFingerprint(
                    mapper,
                    attempt.writeEffectRef(),
                    attempt.observedStateRef(),
                    attempt.observedStateRevision(),
                    attempt.observedWorldFingerprint(),
                    attempt.observedLogicalClock(),
                    attempt.requestFingerprint(),
                    attempt.outcome(),
                    attempt.stage(),
                    attempt.stateDisposition(),
                    attempt.retryable(),
                    attempt.errorCode(),
                    attempt.errorType());
            if (!expected.equals(
                    attempt.failureFingerprint())) {
                throw new IllegalArgumentException(
                        "mirror state write failure fingerprint mismatch");
            }
        }
    }

    private record FailureMaterial(
            MirrorArtifactRef writeEffectRef,
            MirrorArtifactRef observedStateRef,
            long observedStateRevision,
            String observedWorldFingerprint,
            Instant observedLogicalClock,
            String requestFingerprint,
            MirrorStateWriteOutcomeRunEvidence.WriteOutcome outcome,
            MirrorStateWriteOutcomeRunEvidence.WriteStage stage,
            MirrorStateWriteOutcomeRunEvidence.StateDisposition disposition,
            boolean retryable,
            String errorCode,
            String errorType
    ) {
    }
}
