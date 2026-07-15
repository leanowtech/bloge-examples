package com.leanowtech.bloge.gateway.testing.api;

/** Raised when one replay-payload id/revision is reused for different immutable content. */
public final class ReplayPayloadConflictException extends RuntimeException {

    /**
     * Creates an immutable revision conflict.
     *
     * @param message bounded immutable-conflict detail
     */
    public ReplayPayloadConflictException(String message) {
        super(message);
    }
}
