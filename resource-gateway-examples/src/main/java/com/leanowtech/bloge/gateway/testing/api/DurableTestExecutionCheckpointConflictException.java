package com.leanowtech.bloge.gateway.testing.api;

/** Stable fail-closed conflict raised by durable test execution checkpoint persistence. */
public final class DurableTestExecutionCheckpointConflictException extends RuntimeException {

    private final Reason reason;

    public DurableTestExecutionCheckpointConflictException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    /** @return machine-stable conflict category without exposing checkpoint payloads */
    public Reason reason() {
        return reason;
    }

    /** Durable persistence conflicts suitable for security events and recovery decisions. */
    public enum Reason {
        DUPLICATE_IDENTITY,
        STALE_FENCE,
        INVALID_TRANSITION
    }
}
