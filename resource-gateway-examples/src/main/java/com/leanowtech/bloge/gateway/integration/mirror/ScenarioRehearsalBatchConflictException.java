package com.leanowtech.bloge.gateway.integration.mirror;

/**
 * Bounded semantic rejection from the durable Scenario batch queue.
 */
public final class ScenarioRehearsalBatchConflictException
        extends RuntimeException {
    /** Machine-readable conflict class. */
    public enum Reason {
        IDEMPOTENCY_CONFLICT,
        POLICY_MISMATCH,
        DEADLINE_INVALID,
        PLAN_TIMEOUT_EXCEEDED,
        GLOBAL_QUEUE_FULL,
        TENANT_QUEUE_FULL,
        JOB_NOT_FOUND,
        CANCELLATION_CONFLICT,
        EVIDENCE_MISMATCH,
        LEASE_LOST
    }

    private final Reason reason;

    /** Creates one payload-free bounded rejection. */
    public ScenarioRehearsalBatchConflictException(
            Reason reason, String message) {
        super(message);
        this.reason = java.util.Objects.requireNonNull(
                reason, "reason");
    }

    /** @return stable semantic reason */
    public Reason reason() {
        return reason;
    }
}
