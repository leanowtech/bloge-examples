package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;
import java.util.Optional;

/**
 * Resolves the additional authority needed by cancellation and success terminal projections.
 *
 * <p>The physical-attempt observation remains the authority for the runtime terminal fact. This
 * resolver supplies only the second proof required to choose a queue winner: an exact confirmed
 * cancellation entry for {@code CANCELLED}, or an expected signed parent run for
 * {@code SUCCEEDED}. Failure, timeout, and provider-abort projections do not call this boundary.
 * Implementations must not return fixture values, business payloads, credentials, or provider
 * diagnostics.</p>
 *
 * <p>{@link ResolutionStatus#PENDING} is retryable absence of proof. A contradictory or ambiguous
 * retained proof is {@link ResolutionStatus#CONFLICT}; it must not be hidden as temporary
 * unavailability. Storage and authority outages are reported by throwing a runtime exception so
 * the coordinator can preserve the distinction.</p>
 */
@FunctionalInterface
public interface TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver {

    /**
     * Resolves one additional proof candidate for the exact retained physical identity.
     *
     * @param identity exact immutable physical-attempt identity
     * @param disposition either {@code CANCELLED} or {@code SUCCEEDED}
     * @return closed proof resolution; never {@code null}
     */
    Resolution resolve(
            TestSuiteStabilityPhysicalAttemptIdentity identity,
            TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition
                    disposition);

    /** Additional proof kind. */
    enum ProofKind {
        /** Provider-confirmed cancellation bound to the exact attempt and queue intent. */
        CANCELLATION,
        /** Expected parent run and evidence commitment to be reverified transactionally. */
        PARENT_SUCCESS
    }

    /** Closed resolver disposition. */
    enum ResolutionStatus {
        /** One shape-valid proof candidate is ready for transactional reverification. */
        READY,
        /** The required proof has not become authoritative yet and may be retried. */
        PENDING,
        /** Retained proof candidates are contradictory, ambiguous, or permanently invalid. */
        CONFLICT
    }

    /** Fixed-cardinality resolver reason suitable for retry policy and telemetry. */
    enum Reason {
        /** No failure applies to a ready proof. */
        NONE,
        /** No exact provider-confirmed cancellation proof is retained yet. */
        CANCELLATION_NOT_CONFIRMED,
        /** The expected signed parent completion is not authoritative yet. */
        PARENT_NOT_CONFIRMED,
        /** More than one incompatible candidate could claim the same terminal winner. */
        AMBIGUOUS_PROOF,
        /** A retained candidate contradicts the exact attempt or terminal observation. */
        PROOF_CONFLICT
    }

    /**
     * Payload-free proof candidate.
     *
     * @param kind exact additional proof kind
     * @param cancellation exact confirmed cancellation entry only for cancellation
     * @param parentStabilityRunId expected parent run only for success
     * @param parentEvidenceFingerprint expected parent evidence commitment only for success
     */
    record Proof(
            ProofKind kind,
            Optional<TestSuiteStabilityAttemptCancellationJournal.Entry> cancellation,
            String parentStabilityRunId,
            String parentEvidenceFingerprint) {

        /** Enforces one and only one proof shape. */
        public Proof {
            kind = Objects.requireNonNull(kind, "kind");
            cancellation = Objects.requireNonNull(cancellation, "cancellation");
            parentStabilityRunId = normalized(parentStabilityRunId);
            parentEvidenceFingerprint = normalized(parentEvidenceFingerprint);
            boolean cancellationShape = cancellation.isPresent()
                    && parentStabilityRunId.isEmpty()
                    && parentEvidenceFingerprint.isEmpty();
            boolean parentShape = cancellation.isEmpty()
                    && parentStabilityRunId.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}")
                    && parentEvidenceFingerprint.matches("sha256:[a-f0-9]{64}");
            if (kind == ProofKind.CANCELLATION && !cancellationShape
                    || kind == ProofKind.PARENT_SUCCESS && !parentShape) {
                throw new IllegalArgumentException(
                        "Invalid physical-attempt terminal-projection proof");
            }
        }

        /**
         * Creates an exact cancellation proof candidate.
         *
         * @param cancellation confirmed cancellation journal entry
         * @return shape-valid cancellation proof
         */
        public static Proof cancellation(
                TestSuiteStabilityAttemptCancellationJournal.Entry cancellation) {
            return new Proof(ProofKind.CANCELLATION,
                    Optional.of(Objects.requireNonNull(cancellation, "cancellation")), "", "");
        }

        /**
         * Creates an expected signed parent-success proof candidate.
         *
         * @param parentStabilityRunId expected parent run identity
         * @param parentEvidenceFingerprint expected signed evidence commitment
         * @return shape-valid parent-success proof
         */
        public static Proof parentSuccess(
                String parentStabilityRunId, String parentEvidenceFingerprint) {
            return new Proof(ProofKind.PARENT_SUCCESS, Optional.empty(),
                    parentStabilityRunId, parentEvidenceFingerprint);
        }
    }

    /**
     * Closed resolution of one additional proof lookup.
     *
     * @param status exact resolution status
     * @param reason fixed-cardinality reason
     * @param proof present only when ready
     */
    record Resolution(
            ResolutionStatus status, Reason reason, Optional<Proof> proof) {

        /** Enforces ready, pending, and permanent-conflict result shapes. */
        public Resolution {
            status = Objects.requireNonNull(status, "status");
            reason = Objects.requireNonNull(reason, "reason");
            proof = Objects.requireNonNull(proof, "proof");
            if (status == ResolutionStatus.READY
                    && (reason != Reason.NONE || proof.isEmpty())
                    || status != ResolutionStatus.READY
                    && (reason == Reason.NONE || proof.isPresent())
                    || status == ResolutionStatus.PENDING
                    && reason != Reason.CANCELLATION_NOT_CONFIRMED
                    && reason != Reason.PARENT_NOT_CONFIRMED
                    || status == ResolutionStatus.CONFLICT
                    && reason != Reason.AMBIGUOUS_PROOF
                    && reason != Reason.PROOF_CONFLICT) {
                throw new IllegalArgumentException(
                        "Invalid terminal-projection proof resolution");
            }
        }

        /**
         * Returns a ready proof candidate.
         *
         * @param proof exact candidate
         * @return ready resolution
         */
        public static Resolution ready(Proof proof) {
            return new Resolution(ResolutionStatus.READY, Reason.NONE,
                    Optional.of(Objects.requireNonNull(proof, "proof")));
        }

        /**
         * Returns retryable proof absence.
         *
         * @param reason cancellation or parent proof not-yet-confirmed reason
         * @return pending resolution
         */
        public static Resolution pending(Reason reason) {
            return new Resolution(ResolutionStatus.PENDING, reason, Optional.empty());
        }

        /**
         * Returns a permanent proof conflict.
         *
         * @param reason ambiguous or contradictory proof reason
         * @return conflict resolution
         */
        public static Resolution conflict(Reason reason) {
            return new Resolution(ResolutionStatus.CONFLICT, reason, Optional.empty());
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
