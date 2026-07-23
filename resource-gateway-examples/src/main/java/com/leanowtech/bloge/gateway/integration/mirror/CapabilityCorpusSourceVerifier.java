package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;

/**
 * External metadata-only verifier for corpus source usability.
 *
 * <p>The verifier checks that every exact payload, proof, schema, grant, and retention reference
 * remains usable and has not been tombstoned since observation admission. It must not return or
 * log payload bytes. A rejected source is a deterministic governance result; authority
 * unavailability is infrastructure uncertainty and must fail the candidate request with 503.</p>
 */
public interface CapabilityCorpusSourceVerifier {
    /**
     * Reports whether source verification can currently make a trustworthy decision.
     *
     * @return true only while the external reference authority is usable
     */
    boolean available();

    /**
     * Revalidates one admitted source before candidate materialization.
     *
     * @param source exact persisted observation and admission
     * @param policy atomic corpus-governance policy
     * @param verificationTime trusted local verification time
     * @return bounded metadata-only outcome
     */
    VerificationResult verify(
            CapabilityObservationRepository.StoredObservation source,
            CapabilityCorpusGovernancePolicyProvider.GovernancePolicy policy,
            Instant verificationTime);

    /**
     * Returns a fail-closed verifier for deployments without an external payload authority.
     *
     * @return unavailable source verifier
     */
    static CapabilityCorpusSourceVerifier unavailable() {
        return new CapabilityCorpusSourceVerifier() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public VerificationResult verify(
                    CapabilityObservationRepository.StoredObservation source,
                    CapabilityCorpusGovernancePolicyProvider.GovernancePolicy policy,
                    Instant verificationTime) {
                return VerificationResult.unavailable(
                        "CORPUS_SOURCE_AUTHORITY_UNAVAILABLE");
            }
        };
    }

    /** Closed source-verification outcome. */
    enum Outcome {
        /** Every source reference remains exact, authorized, and usable. */
        VERIFIED,
        /** A source was deleted, expired, revoked, or failed external policy. */
        REJECTED,
        /** The external authority could not produce a trustworthy decision. */
        UNAVAILABLE
    }

    /**
     * Payload-free external verification result.
     *
     * @param outcome closed outcome
     * @param reasonCode stable low-cardinality reason
     */
    record VerificationResult(Outcome outcome, String reasonCode) {
        /** Validates a bounded machine-readable result. */
        public VerificationResult {
            outcome = Objects.requireNonNull(outcome, "outcome");
            reasonCode = reasonCode == null ? "" : reasonCode.trim();
            if (!reasonCode.matches("[A-Z][A-Z0-9_.-]{0,127}")) {
                throw new IllegalArgumentException(
                        "corpus source verification reason is invalid");
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
         * Creates a deterministic source rejection.
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
    }
}
