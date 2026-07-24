package com.leanowtech.bloge.gateway.integration.mirror;

/**
 * Bounded fail-closed checkpoint or recovery failure.
 */
public final class MirrorSessionCheckpointException
        extends RuntimeException {
    private final Code code;

    /** Creates a failure without retaining payload-shaped provider detail. */
    public MirrorSessionCheckpointException(Code code) {
        super(java.util.Objects.requireNonNull(code, "code").name());
        this.code = code;
    }

    /**
     * Returns the bounded failure category used by the authenticated API boundary.
     *
     * @return stable internal failure category
     */
    public Code code() {
        return code;
    }

    /** Stable checkpoint failure categories mapped by the authenticated API boundary. */
    public enum Code {
        SIGNER_UNAVAILABLE,
        INVALID,
        GENERATION_CONFLICT,
        DEPENDENCY_CONFLICT,
        STATE_CONFLICT
    }
}
