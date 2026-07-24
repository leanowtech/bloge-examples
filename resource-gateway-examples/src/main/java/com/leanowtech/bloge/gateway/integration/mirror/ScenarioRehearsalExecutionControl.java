package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;

/**
 * Server-owned cooperative control boundary for one Scenario rehearsal aggregate.
 *
 * <p>The runtime calls this boundary before external case execution, after each durable case
 * checkpoint, and before aggregate evidence commit. Implementations may observe durable
 * cancellation, deadlines, or lease loss and fail closed with
 * {@link ScenarioRehearsalExecutionControlException}. The callback receives only progress
 * coordinates and must not receive fixture values, graph context, or business payload.</p>
 */
@FunctionalInterface
public interface ScenarioRehearsalExecutionControl {

    /**
     * Checks whether execution may proceed at one payload-free progress boundary.
     *
     * @param checkpoint exact phase and durable next-case cursor
     */
    void checkpoint(Checkpoint checkpoint);

    /** @return inert control used by direct, non-batch rehearsal calls */
    static ScenarioRehearsalExecutionControl uncontrolled() {
        return checkpoint -> {
            // Direct rehearsal execution has no enclosing batch authority.
        };
    }

    /** Stable cooperative-control phase vocabulary. */
    enum Phase {
        BEFORE_RESOLUTION,
        BEFORE_CASE,
        AFTER_CASE,
        BEFORE_COMMIT
    }

    /**
     * Payload-free aggregate progress coordinates supplied to the control plane.
     *
     * @param phase current execution boundary
     * @param nextCaseIndex first case not durably checkpointed
     * @param totalCases immutable aggregate case count
     */
    record Checkpoint(
            Phase phase,
            int nextCaseIndex,
            int totalCases
    ) {
        /** Validates a cursor inside the immutable case closure. */
        public Checkpoint {
            phase = Objects.requireNonNull(phase, "phase");
            if (totalCases < 1
                    || totalCases > ScenarioPack.MAXIMUM_CASES
                    || nextCaseIndex < 0
                    || nextCaseIndex > totalCases) {
                throw new IllegalArgumentException(
                        "Scenario execution-control checkpoint is invalid");
            }
            if (phase == Phase.BEFORE_CASE
                    && nextCaseIndex == totalCases) {
                throw new IllegalArgumentException(
                        "Before-case checkpoint requires an unfinished case");
            }
        }
    }
}
