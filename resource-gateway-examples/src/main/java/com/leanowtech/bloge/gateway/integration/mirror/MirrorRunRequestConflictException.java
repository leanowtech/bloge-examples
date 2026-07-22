package com.leanowtech.bloge.gateway.integration.mirror;

/** Raised when one scoped mirror request id is reused with different immutable semantics. */
public final class MirrorRunRequestConflictException extends IllegalArgumentException {
    /** Creates a payload-free idempotency conflict. */
    public MirrorRunRequestConflictException() {
        super("mirror request id already identifies different immutable execution inputs");
    }
}
