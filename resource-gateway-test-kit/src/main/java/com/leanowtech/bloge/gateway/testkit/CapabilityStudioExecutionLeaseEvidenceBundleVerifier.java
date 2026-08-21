package com.leanowtech.bloge.gateway.testkit;

import com.leanowtech.bloge.gateway.testkit.CapabilityStudioExecutionLeaseTranscript.Transcript;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider
        .EvidenceFailureKind;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Independent read-only verifier for one durable full-evidence transcript wrapper.
 *
 * <p>This verifier does not load a Provider, acquire or create a publication lock, repair an
 * attempt, or mutate any sibling. It verifies the retained committed source, owner hard-link
 * claim, complete attempt chain, strict commit manifest, final transcript, receipt, witness, and
 * the caller's out-of-band Stage Result raw and formal outer fingerprints.</p>
 */
public final class CapabilityStudioExecutionLeaseEvidenceBundleVerifier {
    private CapabilityStudioExecutionLeaseEvidenceBundleVerifier() {
    }

    /**
     * Compatibility view of the closed failure category.
     *
     * @deprecated use {@link EvidenceFailureKind}; this verifier cannot produce REJECTED
     */
    @Deprecated(forRemoval = false)
    public enum FailureKind {
        /** The durable wrapper or one of its governed coordinates is structurally invalid. */
        INVALID,
        /** Required filesystem, metadata, permission, or I/O capability is unavailable. */
        UNAVAILABLE
    }

    /** Typed payload-free verification failure. */
    public static final class VerificationException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        /** Closed payload-free failure category. */
        private final EvidenceFailureKind failureKind;

        VerificationException(FailureKind kind) {
            this(EvidenceFailureKind.valueOf(
                    Objects.requireNonNull(kind, "kind is required").name()));
        }

        VerificationException(EvidenceFailureKind failureKind) {
            super("execution lease evidence verification failed");
            if (failureKind == EvidenceFailureKind.REJECTED) {
                throw new IllegalArgumentException("verification failure kind is invalid");
            }
            this.failureKind = Objects.requireNonNull(
                    failureKind, "failureKind is required");
        }

        /**
         * Returns the closed failure category.
         *
         * @return invalid or unavailable
         */
        public FailureKind kind() {
            return FailureKind.valueOf(failureKind.name());
        }

        /**
         * Returns the cross-SPI payload-free failure category.
         *
         * @return invalid or unavailable
         */
        public EvidenceFailureKind failureKind() {
            return failureKind;
        }

        /** Redacted representation. */
        @Override
        public String toString() {
            return "VerificationException[kind=" + failureKind + "]";
        }
    }

    /**
     * Payload-free verified closure coordinates.
     *
     * @param evidenceTransactionId stable transaction identity
     * @param transcriptRawFingerprint exact final transcript bytes fingerprint
     * @param transcriptFingerprint canonical transcript fingerprint
     * @param leaseReceiptFingerprint exact receipt fingerprint
     * @param transitionWitnessFingerprint exact witness fingerprint
     */
    public record Verification(
            String evidenceTransactionId,
            String transcriptRawFingerprint,
            String transcriptFingerprint,
            String leaseReceiptFingerprint,
            String transitionWitnessFingerprint) {
        @Override
        public String toString() {
            return "Verification[material=REDACTED]";
        }
    }

    /**
     * Compatibility overload that fails closed because no out-of-band publication pin is
     * supplied.
     *
     * @param finalTranscript absolute normalized final transcript path
     * @param expectedStageResultRawFingerprint out-of-band exact Stage Result bytes pin
     * @param expectedFormalOuterFingerprint out-of-band formal Provider outer pin
     * @return never returns
     * @throws VerificationException for a closed invalid or unavailable result
     * @deprecated use the four-argument overload with the deployment publication pin
     */
    @Deprecated(forRemoval = false)
    public static Verification verify(
            Path finalTranscript,
            String expectedStageResultRawFingerprint,
            String expectedFormalOuterFingerprint) {
        throw new VerificationException(EvidenceFailureKind.INVALID);
    }

    /**
     * Verifies a complete wrapper and its pre-provisioned publication declaration without
     * writing, repairing, or acquiring the publication lock.
     *
     * @param finalTranscript absolute normalized final transcript path
     * @param expectedStageResultRawFingerprint out-of-band exact Stage Result bytes pin
     * @param expectedFormalOuterFingerprint out-of-band formal Provider outer pin
     * @param expectedPublicationFingerprint out-of-band publication declaration pin
     * @return verified payload-free closure coordinates
     * @throws VerificationException for a closed invalid or unavailable result
     */
    public static Verification verify(
            Path finalTranscript,
            String expectedStageResultRawFingerprint,
            String expectedFormalOuterFingerprint,
            String expectedPublicationFingerprint) {
        Transcript transcript = CapabilityStudioExecutionLeaseEvidenceCli
                .verifyPublishedEvidence(finalTranscript,
                        expectedStageResultRawFingerprint,
                        expectedFormalOuterFingerprint,
                        expectedPublicationFingerprint);
        return new Verification(transcript.evidenceTransactionId(),
                CapabilityStudioExecutionLeaseEvidenceCli.sha256ForEvidence(
                        transcript.bytes()),
                transcript.transcriptFingerprint(),
                transcript.executionLeaseReceipt().fingerprint(),
                transcript.executionLeaseTransitionWitness().fingerprint());
    }
}
