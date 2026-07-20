package com.leanowtech.bloge.gateway.testing.api;

/**
 * Signals an inconsistent replay-payload envelope, descriptor, value, or lifecycle commitment.
 *
 * <p>The stable message contains no payload, scope identity, source lineage, or credential fact, so
 * the rejection can cross service and audit boundaries without leaking governed material.</p>
 */
public final class ReplayPayloadIntegrityException extends IllegalArgumentException {

    private static final String MESSAGE =
            "Replay payload storage requires a canonical immutable integrity record";

    /** Creates a payload-free replay-vault integrity failure. */
    public ReplayPayloadIntegrityException() {
        super(MESSAGE);
    }

    /** Retains an internal canonicalization cause without changing the safe external message. */
    public ReplayPayloadIntegrityException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
