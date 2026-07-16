package com.leanowtech.bloge.gateway.testing.api;

/** Stable fail-closed conflict raised by durable test execution checkpoint persistence. */
public final class DurableTestExecutionCheckpointConflictException extends RuntimeException {

    /** Machine-stable category safe for recovery decisions and sanitized audit events. */
    private final Reason reason;

    /**
     * Creates a fail-closed persistence conflict.
     *
     * @param reason machine-stable conflict category
     * @param message payload-free diagnostic description
     */
    public DurableTestExecutionCheckpointConflictException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    /**
     * Returns the machine-stable category without exposing checkpoint payloads.
     *
     * @return conflict category
     */
    public Reason reason() {
        return reason;
    }

    /** Durable persistence conflicts suitable for security events and recovery decisions. */
    public enum Reason {
        /** A run or engine identity already belongs to different immutable content. */
        DUPLICATE_IDENTITY,
        /** The scoped owner, epoch, revision, or checkpoint fingerprint no longer matches. */
        STALE_FENCE,
        /** The exact known lease has not yet expired according to the database clock. */
        LEASE_ACTIVE,
        /** A recovery worker attempted to renew an owner lease after its database deadline. */
        LEASE_EXPIRED,
        /** A worker handoff is valid in shape but has no committed issuance record. */
        UNRECOGNIZED_DISPATCH,
        /** The exact known checkpoint is terminal or permanently unavailable. */
        NOT_RESUMABLE,
        /** A scoped client request key already identifies different resume intent. */
        IDEMPOTENCY_CONFLICT,
        /** The requested lifecycle or monotonic state transition violates the protocol. */
        INVALID_TRANSITION
    }
}
