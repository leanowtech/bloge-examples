package com.leanowtech.bloge.gateway.integration.mirror;

/** Raised when a mirror execution tries to commit after its durable authority is no longer valid. */
public final class MirrorRunLeaseLostException extends IllegalStateException {
    /** Creates a payload-free fencing failure. */
    public MirrorRunLeaseLostException() {
        super("mirror run lease expired or was replaced before terminal evidence commit");
    }
}
