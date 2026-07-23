package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;

/**
 * Stable payload-free failure raised by the mirror session data-plane store.
 */
public final class MirrorSessionStateStoreException extends RuntimeException {
    private final Code code;
    private final long retryAfterSeconds;

    /**
     * Creates one stable store failure without business payload or provider diagnostics.
     *
     * @param code fixed failure classification
     * @param retryAfterSeconds positive retry delay for retryable failures, otherwise zero
     */
    public MirrorSessionStateStoreException(Code code, long retryAfterSeconds) {
        super(Objects.requireNonNull(code, "code").wireCode());
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
        if ((code.retryable() && retryAfterSeconds < 1)
                || (!code.retryable() && retryAfterSeconds != 0)) {
            throw new IllegalArgumentException(
                    "retry delay must match store failure retryability");
        }
    }

    /** @return fixed store failure classification */
    public Code code() {
        return code;
    }

    /** @return bounded suggested retry delay, or zero for terminal failures */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** Store failure vocabulary exposed only through stable integration problems. */
    public enum Code {
        NOT_FOUND("RG.MIRROR.SESSION.NOT_FOUND", false),
        CREATE_CONFLICT("RG.MIRROR.SESSION.CREATE_CONFLICT", false),
        SESSION_ID_CONFLICT("RG.MIRROR.SESSION.ID_CONFLICT", false),
        GONE("RG.MIRROR.SESSION.GONE", false),
        LEASE_BUSY("RG.MIRROR.SESSION.LEASE_BUSY", true),
        LEASE_LOST("RG.MIRROR.SESSION.LEASE_LOST", true),
        STATE_CONFLICT("RG.MIRROR.SESSION.STATE_CONFLICT", true),
        CAPACITY_EXCEEDED("RG.MIRROR.SESSION.CAPACITY_EXCEEDED", true),
        CORRUPT("RG.MIRROR.SESSION.STATE_CORRUPT", false),
        UNAVAILABLE("RG.MIRROR.SESSION.STORE_UNAVAILABLE", true);

        private final String wireCode;
        private final boolean retryable;

        Code(String wireCode, boolean retryable) {
            this.wireCode = wireCode;
            this.retryable = retryable;
        }

        /** @return stable machine-readable problem code */
        public String wireCode() {
            return wireCode;
        }

        /** @return whether a bounded retry may succeed without changing the command */
        public boolean retryable() {
            return retryable;
        }
    }
}
