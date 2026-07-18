package com.leanowtech.bloge.gateway.testing.api;

/** Stable persistence conflict for the durable suite-stability parent queue. */
public final class TestSuiteStabilityJobConflictException extends RuntimeException {

    /** Closed conflict vocabulary mapped by the application service. */
    public enum Reason {
        IDEMPOTENCY_CONFLICT,
        REPLAY_WINDOW_EXPIRED,
        POLICY_DRIFT,
        GLOBAL_QUEUE_FULL,
        TENANT_QUEUE_FULL,
        NOT_FOUND,
        LEASE_LOST,
        TERMINAL_CONFLICT,
        CANCELLATION_CONFLICT
    }

    private final Reason reason;

    /**
     * @param reason machine-stable conflict reason
     * @param message bounded internal diagnostic
     */
    public TestSuiteStabilityJobConflictException(Reason reason, String message) {
        super(message);
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
    }

    /** @return machine-stable conflict reason */
    public Reason reason() {
        return reason;
    }
}
