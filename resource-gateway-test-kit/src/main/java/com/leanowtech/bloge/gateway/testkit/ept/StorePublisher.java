package com.leanowtech.bloge.gateway.testkit.ept;


/**
 * Injected Store publisher for EPT idempotent external publication.
 *
 * <p>StorePublisher provides a stable issuer identity via {@link #issuer()}.
 * The EPT captures the issuer at construction time and validates it is non-blank.
 * The issuer is embedded in every B1 and R1 receipt for audit traceability.</p>
 *
 * <p>All StorePublisher operations are called inside the striped lock.</p>
 *
 * <p>Design constraints:
 * <ul>
 *   <li>{@link #issuer()} must return a stable, non-blank string on every call.</li>
 *   <li>Acceptance records carry no self-reported fingerprint; the EPT computes
 *       the codec fingerprint deterministically from the sealed receipt body.</li>
 *   <li>The adapter must NOT fabricate arbitrary JSON bytes or self-report fingerprints.</li>
 * </ul>
 */
public interface StorePublisher {

    /**
     * Stable issuer identity for all receipts produced by this publisher.
     * The EPT captures this value at construction and validates non-blank.
     *
     * @return non-blank issuer string used as the {@code issuer} field in B1 and R1 receipts
     */
    String issuer();

    /**
     * Publishes one B1 acceptance for the given B0 closure fingerprint.
     *
     * <p>The adapter MUST use {@code b0ClosureFingerprint} and {@code idempotencyKey}
     * as the idempotency anchor.  Producing the same acceptance for the same
     * idempotency key is the caller's responsibility.</p>
     *
     * <p>The returned acceptance carries no self-reported fingerprint; the EPT
     * computes the codec fingerprint from the sealed receipt body.</p>
     *
     * @param b0ClosureFingerprint B0 closure fingerprint (used as idempotency anchor)
     * @param idempotencyKey stable idempotency key for this publication
     * @return B1 acceptance carrying the store issuer and idempotency key
     * @throws StorePublisherException on unavailable or timeout
     */
    B1Acceptance publishB1(String b0ClosureFingerprint, String idempotencyKey);

    /**
     * Issues one R1 final outer commitment acceptance.
     *
     * <p>The returned acceptance carries no self-reported fingerprint; the EPT
     * computes the codec fingerprint from the sealed receipt body and embeds
     * the provided {@code b1ReceiptFingerprint} as the B1 binding anchor.</p>
     *
     * @param b0ClosureFingerprint B0 closure fingerprint
     * @param b1ReceiptFingerprint B1 receipt fingerprint (binding anchor)
     * @param idempotencyKey stable idempotency key
     * @param owner stable owner identity bound to this commitment
     * @return R1 acceptance carrying the store issuer and owner
     * @throws StorePublisherException on unavailable or timeout
     */
    R1Acceptance issueR1(String b0ClosureFingerprint,
                          String b1ReceiptFingerprint,
                          String idempotencyKey,
                          String owner);

    /**
     * Queries for an existing B1 acceptance using the idempotency key.
     * Used during EXTERNAL_PENDING recovery.
     *
     * @param idempotencyKey stable idempotency key
     * @return existing B1 acceptance, or null if not yet accepted
     */
    B1Acceptance queryB1(String idempotencyKey);

    // -------------------------------------------------------------------------
    // Acceptance records (identity-only; no bytes or self-reported fingerprint)
    // -------------------------------------------------------------------------

    /**
     * B1 acceptance from StorePublisher.publishB1.
     *
     * <p>Carries only the store issuer and idempotency key.
     * No receipt bytes or self-reported fingerprint.</p>
     *
     * @param issuer stable issuer identity (must be non-blank)
     * @param idempotencyKey idempotency key used for this publication (must be non-blank)
     */
    record B1Acceptance(String issuer, String idempotencyKey) {
        /** Validates issuer and idempotencyKey are non-blank. */
        public B1Acceptance {
            if (issuer == null || issuer.isBlank()) {
                throw new IllegalArgumentException("issuer required");
            }
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                throw new IllegalArgumentException("idempotencyKey required");
            }
        }
    }

    /**
     * R1 acceptance from StorePublisher.issueR1.
     *
     * <p>Carries only the store issuer and owner.
     * No receipt bytes or self-reported fingerprint.</p>
     *
     * @param issuer stable issuer identity (must be non-blank)
     * @param owner stable owner identity (must be non-blank)
     */
    record R1Acceptance(String issuer, String owner) {
        /** Validates issuer and owner are non-blank. */
        public R1Acceptance {
            if (issuer == null || issuer.isBlank()) {
                throw new IllegalArgumentException("issuer required");
            }
            if (owner == null || owner.isBlank()) {
                throw new IllegalArgumentException("owner required");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Exception
    // -------------------------------------------------------------------------

    /** Checked exception for StorePublisher failures. */
    class StorePublisherException extends RuntimeException {
        /** Stable failure code without credentials. */
        private final String code;
        /** True if retry with same idempotencyKey may succeed. */
        private final boolean recoverable;

        /**
         * Constructs a StorePublisherException.
         * @param code stable failure code
         * @param message failure description
         * @param recoverable whether retry with same idempotencyKey may succeed
         */
        public StorePublisherException(String code, String message, boolean recoverable) {
            super(message);
            this.code = code;
            this.recoverable = recoverable;
        }

        /**
         * Constructs a StorePublisherException with a cause.
         * @param code stable failure code
         * @param message failure description
         * @param recoverable whether retry with same idempotencyKey may succeed
         * @param cause underlying exception
         */
        public StorePublisherException(String code, String message, boolean recoverable, Throwable cause) {
            super(message, cause);
            this.code = code;
            this.recoverable = recoverable;
        }

        /**
         * The stable failure code.
         * @return failure code
         */
        public String code() { return code; }
        /**
         * Whether retry with the same idempotencyKey may succeed.
         * @return true if recoverable
         */
        public boolean recoverable() { return recoverable; }
    }
}
