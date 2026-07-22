package com.leanowtech.bloge.gateway.testing.runtime;

import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan;

import java.time.Instant;
import java.util.Objects;

/**
 * Result of executing a sealed mirror generation through the independent BLOGE test engine.
 *
 * @param plan exact public plan admitted for the run
 * @param admittedAt server admission time used for TTL enforcement
 * @param execution graph result and semantically fingerprinted evidence from the shared kernel
 */
public record MirrorRunResult(
        MirrorPlan plan,
        Instant admittedAt,
        TestExecutionResult execution
) {
    /** Requires one complete admitted execution result. */
    public MirrorRunResult {
        plan = Objects.requireNonNull(plan, "plan");
        admittedAt = Objects.requireNonNull(admittedAt, "admittedAt");
        execution = Objects.requireNonNull(execution, "execution");
    }

    /** @return whether graph execution and all fixture assertions passed */
    public boolean passed() {
        return execution.passed();
    }
}
