package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;

/** Raised when a stability execution cannot cross an exact persistence fence. */
public final class TestSuiteStabilityRunConflictException extends RuntimeException {
    /** Stable conflict categories mapped to non-payload API problem codes. */
    public enum Reason {
        /** A scoped idempotency identity already represents different immutable intent. */
        IDEMPOTENCY_CONFLICT,
        /** Retention expired but the idempotency tombstone still forbids identity reuse. */
        IDEMPOTENCY_RETIRED,
        /** The caller no longer owns the exact database-clock-live execution lease. */
        LEASE_LOST,
        /** A terminal record already occupies the deterministic stability identity. */
        TERMINAL_CONFLICT
    }

    private final Reason reason;

    /**
     * @param reason stable machine-readable conflict category
     * @param message bounded persistence conflict description
     */
    public TestSuiteStabilityRunConflictException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Returns the stable category without exposing database values.
     *
     * @return conflict reason
     */
    public Reason reason() {
        return reason;
    }
}
