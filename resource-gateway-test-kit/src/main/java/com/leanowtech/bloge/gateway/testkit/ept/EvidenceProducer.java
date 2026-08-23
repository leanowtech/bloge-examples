package com.leanowtech.bloge.gateway.testkit.ept;

import java.nio.file.Path;

/**
 * Injected deterministic sealed candidate producer for EPT.
 *
 * <p>Called exactly once per fresh execute.  The producer must be deterministic:
 * the same stableRequestId + candidateFingerprint produces the same sealed candidate,
 * and the returned evidence-root path is stable across calls.</p>
 *
 * <p>The producer is called inside the striped lock after preflight checks pass and
 * before the B0 manifest is computed.</p>
 */
@FunctionalInterface
public interface EvidenceProducer {

    /**
     * Produces one deterministic sealed evidence candidate.
     *
     * @param stableRequestId stable request identity (internally derived from six fingerprints)
     * @param candidateFingerprint deterministic digest of the bounded child execution artifact
     * @param workingDirectory private working directory (0700) for candidate staging
     * @return sealed evidence candidate
     * @throws EvidenceProducerException on capability unavailable or production failure
     */
    SealedEvidenceCandidate produce(String stableRequestId,
                                    String candidateFingerprint,
                                    Path workingDirectory);

    /**
     * Deterministic sealed evidence candidate produced by bounded child.
     *
     * @param evidenceRoot stable path to the evidence-root directory (never null)
     * @param candidateDigest stable sealed candidate digest (equal to candidateFingerprint from request)
     * @param attemptGeneration attempt generation for idempotency
     */
    record SealedEvidenceCandidate(
            Path evidenceRoot,
            String candidateDigest,
            int attemptGeneration) {

        /** Validates evidenceRoot, candidateDigest, and attemptGeneration. */
        public SealedEvidenceCandidate {
            if (evidenceRoot == null) {
                throw new IllegalArgumentException("evidenceRoot required");
            }
            if (candidateDigest == null
                    || !candidateDigest.matches("^sha256:[0-9a-f]{64}$")) {
                throw new IllegalArgumentException("candidateDigest must be sha256:hex64");
            }
            if (attemptGeneration < 1) {
                throw new IllegalArgumentException("attemptGeneration must be >= 1");
            }
        }
    }

    /** Checked exception for evidence producer failures. */
    class EvidenceProducerException extends RuntimeException {
        /** Stable failure code without credentials. */
        private final String code;

        /**
         * Constructs an EvidenceProducerException.
         * @param code stable failure code
         * @param message failure description
         */
        public EvidenceProducerException(String code, String message) {
            super(message);
            this.code = code;
        }

        /**
         * Constructs an EvidenceProducerException with a cause.
         * @param code stable failure code
         * @param message failure description
         * @param cause underlying exception
         */
        public EvidenceProducerException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        /**
         * Stable failure code without credentials.
         * @return failure code
         */
        public String code() {
            return code;
        }
    }
}
