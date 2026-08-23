package com.leanowtech.bloge.gateway.testkit.ept;

import java.util.Objects;

/**
 * Closed verdict for one EPT execute round-trip.
 *
 * <p>Public outcome is exactly one of COMMITTED, RECOVERED, or CLOSED.
 * The B0Receipt is present when outcome is COMMITTED or RECOVERED.
 * When outcome is CLOSED, closedCategory and reasonCode are present.</p>
 */
public sealed interface Verdict {

    /**
     * The outcome of the execute round-trip.
     * @return outcome (COMMITTED, RECOVERED, or CLOSED)
     */
    Outcome outcome();

    /**
     * The closed failure category, present only when outcome is CLOSED.
     * @return closed category, null for COMMITTED/RECOVERED
     */
    ClosedCategory closedCategory();

    /**
     * The stable failure reason code, present only when outcome is CLOSED.
     * @return reason code, null for COMMITTED/RECOVERED
     */
    String reasonCode();

    /**
     * The transaction identity.
     * @return transaction identity
     */
    String transactionId();
    /**
     * The stable request identity.
     * @return stable request identity
     */
    String stableRequestId();
    /**
     * The B0 receipt, present when outcome is COMMITTED or RECOVERED.
     * @return B0 receipt or null for CLOSED
     */
    B0Receipt b0Receipt();

    /** Outcome of one EPT execute round-trip. */
    enum Outcome {
        /** Execute completed successfully; B0 + B1 + R1 all durable. */
        COMMITTED,
        /** Exact retry recovered existing committed state; no re-execution. */
        RECOVERED,
        /** Execute did not complete; closed with a stable failure category. */
        CLOSED
    }

    /**
     * B0 + B1 + R1 + authority fingerprints for COMMITTED and RECOVERED.
     *
     * @param b0RawFingerprint SHA-256 of exact b0-inner-manifest raw bytes
     * @param b0CanonicalFingerprint SHA-256 of canonical b0-inner-manifest
     * @param b0ClosureFingerprint SHA-256 of b0 closure with §E.2 six-element identity
     * @param b1ReceiptFingerprint SHA-256 of B1 Store receipt bytes
     * @param r1Fingerprint SHA-256 of R1 final commitment receipt bytes
     * @param authorityFingerprint SHA-256 of fencing authority token bytes
     * @param authorityEpoch positive epoch from fencing authority
     */
    record B0Receipt(
            String b0RawFingerprint,
            String b0CanonicalFingerprint,
            String b0ClosureFingerprint,
            String b1ReceiptFingerprint,
            String r1Fingerprint,
            String authorityFingerprint,
            long authorityEpoch) {

        /** Validates all fingerprint fields are non-null and authorityEpoch is positive. */
        public B0Receipt {
            Objects.requireNonNull(b0RawFingerprint, "b0RawFingerprint required");
            Objects.requireNonNull(b0CanonicalFingerprint, "b0CanonicalFingerprint required");
            Objects.requireNonNull(b0ClosureFingerprint, "b0ClosureFingerprint required");
            Objects.requireNonNull(b1ReceiptFingerprint, "b1ReceiptFingerprint required");
            Objects.requireNonNull(r1Fingerprint, "r1Fingerprint required");
            Objects.requireNonNull(authorityFingerprint, "authorityFingerprint required");
            if (authorityEpoch <= 0) {
                throw new IllegalArgumentException("authorityEpoch must be > 0");
            }
        }
    }

    /**
     * Creates a committed verdict.
     * @param transactionId transaction identity
     * @param stableRequestId stable request identity
     * @param receipt B0 receipt
     * @return committed verdict
     */
    static Verdict committed(String transactionId, String stableRequestId, B0Receipt receipt) {
        return new Committed(transactionId, stableRequestId, Objects.requireNonNull(receipt, "receipt required"));
    }

    /**
     * Creates a recovered verdict.
     * @param transactionId transaction identity
     * @param stableRequestId stable request identity
     * @param receipt B0 receipt
     * @return recovered verdict
     */
    static Verdict recovered(String transactionId, String stableRequestId, B0Receipt receipt) {
        return new Recovered(transactionId, stableRequestId, Objects.requireNonNull(receipt, "receipt required"));
    }

    /**
     * Creates a closed verdict.
     * @param transactionId transaction identity
     * @param stableRequestId stable request identity
     * @param category closed category
     * @param reasonCode failure reason code
     * @return closed verdict
     */
    static Verdict closed(String transactionId, String stableRequestId,
                          ClosedCategory category, String reasonCode) {
        return new Closed(transactionId, stableRequestId,
                Objects.requireNonNull(category, "category required"),
                Objects.requireNonNull(reasonCode, "reasonCode required"));
    }

    /**
     * COMMITTED outcome record.
     * @param transactionId transaction identity
     * @param stableRequestId stable request identity
     * @param b0Receipt B0 receipt
     */
    record Committed(String transactionId, String stableRequestId, B0Receipt b0Receipt) implements Verdict {
        @Override public Outcome outcome() { return Outcome.COMMITTED; }
        @Override public ClosedCategory closedCategory() { return null; }
        @Override public String reasonCode() { return null; }
    }

    /**
     * RECOVERED outcome record.
     * @param transactionId transaction identity
     * @param stableRequestId stable request identity
     * @param b0Receipt B0 receipt
     */
    record Recovered(String transactionId, String stableRequestId, B0Receipt b0Receipt) implements Verdict {
        @Override public Outcome outcome() { return Outcome.RECOVERED; }
        @Override public ClosedCategory closedCategory() { return null; }
        @Override public String reasonCode() { return null; }
    }

    /**
     * CLOSED outcome record.
     * @param transactionId transaction identity
     * @param stableRequestId stable request identity
     * @param closedCategory closed failure category
     * @param reasonCode failure reason code
     */
    record Closed(String transactionId, String stableRequestId,
                  ClosedCategory closedCategory, String reasonCode) implements Verdict {
        @Override public Outcome outcome() { return Outcome.CLOSED; }
        @Override public B0Receipt b0Receipt() { return null; }
    }
}
