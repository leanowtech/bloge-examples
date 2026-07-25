package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;

/**
 * Stable business conflict raised by the reviewed Scenario remediation state machine.
 */
public final class ScenarioRehearsalRemediationConflictException
        extends RuntimeException {
    /** Closed reasons mapped to stable integration problem codes. */
    public enum Reason {
        NOT_FOUND,
        IDEMPOTENCY_CONFLICT,
        PREDECESSOR_NOT_TERMINAL,
        PREDECESSOR_NOT_BLOCKED,
        WORKBOOK_FINGERPRINT_MISMATCH,
        EVIDENCE_CLOSURE_INVALID,
        REPLACEMENT_FENCE_MISMATCH,
        REPLACEMENT_PLAN_NOT_FOUND,
        PLAN_FINGERPRINT_MISMATCH,
        POLICY_MISMATCH,
        PLAN_EXPIRED,
        PLAN_REJECTED,
        APPROVAL_GENERATION_MISMATCH,
        APPROVAL_ORDER_INVALID,
        DISTINCT_ACTOR_REQUIRED,
        GOVERNANCE_TICKET_MISMATCH,
        APPROVALS_INCOMPLETE,
        ALREADY_SUBMITTED,
        SUCCESSOR_IDENTITY_ALREADY_USED
    }

    private final Reason reason;

    /** Creates a stable conflict without embedding business payload. */
    public ScenarioRehearsalRemediationConflictException(
            Reason reason,
            String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /** @return stable machine-readable conflict reason */
    public Reason reason() {
        return reason;
    }
}
