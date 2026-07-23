package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;

/**
 * External vault and sanitization-proof verification boundary.
 *
 * <p>The verifier must prove that every referenced payload exists in the exact tenant scope, is
 * immutable, was sanitized before persistence, and is bound to the submitted schema, proof,
 * purpose grant, residency, and retention metadata. Resource Gateway receives only the bounded
 * result; implementations must not return or log payload bytes.</p>
 */
public interface CapabilityObservationPayloadReferenceVerifier {
    /**
     * Reports whether the external reference authority can currently make a decision.
     *
     * @return true only when reference verification is usable
     */
    boolean available();

    /**
     * Verifies request and optional response references without loading payloads into the gateway.
     *
     * @param envelope signed payload-free observation
     * @param policy exact operator-owned admission policy
     * @param verificationTime trusted local admission time
     * @return bounded verified, rejected, or unavailable result
     */
    VerificationResult verify(
            CapabilityObservationEnvelope envelope,
            CapabilityObservationAdmissionPolicyProvider.AdmissionPolicy policy,
            Instant verificationTime);

    /**
     * Returns a fail-closed verifier for deployments without a governed payload vault.
     *
     * @return unavailable verifier
     */
    static CapabilityObservationPayloadReferenceVerifier unavailable() {
        return new CapabilityObservationPayloadReferenceVerifier() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public VerificationResult verify(
                    CapabilityObservationEnvelope envelope,
                    CapabilityObservationAdmissionPolicyProvider.AdmissionPolicy policy,
                    Instant verificationTime) {
                return VerificationResult.unavailable(
                        "PAYLOAD_REFERENCE_AUTHORITY_UNAVAILABLE");
            }
        };
    }

    /** Closed external reference-verification outcome. */
    enum Outcome {
        /** Every external payload and proof reference is exact and usable. */
        VERIFIED,
        /** One or more references failed vault, proof, scope, or lifecycle policy. */
        REJECTED,
        /** The external authority could not make a trustworthy decision. */
        UNAVAILABLE
    }

    /**
     * Payload-free verification result.
     *
     * @param outcome closed result
     * @param reasonCode stable low-cardinality reason
     */
    record VerificationResult(Outcome outcome, String reasonCode) {
        /** Validates a bounded machine-readable result. */
        public VerificationResult {
            outcome = Objects.requireNonNull(outcome, "outcome");
            reasonCode = reasonCode == null ? "" : reasonCode.trim();
            if (!reasonCode.matches("[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException(
                        "payload-reference verification reason is invalid");
            }
        }

        /**
         * Creates a successful result.
         *
         * @return verified result
         */
        public static VerificationResult verified() {
            return new VerificationResult(Outcome.VERIFIED, "VERIFIED");
        }

        /**
         * Creates a governed rejection.
         *
         * @param reasonCode stable rejection reason
         * @return rejected result
         */
        public static VerificationResult rejected(String reasonCode) {
            return new VerificationResult(Outcome.REJECTED, reasonCode);
        }

        /**
         * Creates an infrastructure-unavailable result.
         *
         * @param reasonCode stable unavailability reason
         * @return unavailable result
         */
        public static VerificationResult unavailable(String reasonCode) {
            return new VerificationResult(Outcome.UNAVAILABLE, reasonCode);
        }

        /**
         * Reports whether every external reference check passed.
         *
         * @return true only for verified references
         */
        public boolean verifiedResult() {
            return outcome == Outcome.VERIFIED;
        }
    }
}
