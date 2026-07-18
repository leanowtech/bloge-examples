package com.leanowtech.bloge.gateway.testing.api;

/**
 * Cooperative control boundary around one suite-stability parent execution.
 *
 * <p>The ordinary synchronous API uses {@link #uncontrolled()}. A durable worker may bind a queue
 * lease, renew it at checkpoints, terminalize parent progress when cancellation or deadline wins,
 * and linearize queue completion before the signed parent record is published. Implementations
 * must throw on any ambiguous control decision; the execution service never treats ambiguity as
 * permission to continue.</p>
 */
public interface TestSuiteStabilityExecutionControl {

    /** Stable checkpoints that never carry fixture, context, source output, or business payload. */
    enum Phase {
        /** Before governed source evidence is restored from a durable parent prefix. */
        BEFORE_PROGRESS_RESTORE,
        /** Before a new child suite attempt is submitted. */
        BEFORE_ATTEMPT,
        /** After source evidence verification and before its parent journal checkpoint. */
        AFTER_SOURCE_VERIFICATION,
        /** After the complete horizon and before evidence evaluation and signing. */
        BEFORE_EVIDENCE_SEAL
    }

    /**
     * Binds this controller to one immutable parent identity before work or replay completion.
     *
     * @param execution payload-free immutable parent identity
     */
    void executionStarted(TestSuiteStabilityExecutionDescriptor execution);

    /**
     * Verifies that execution may continue at one bounded cooperative checkpoint.
     *
     * @param phase stable control phase
     * @param attempt one-based attempt for attempt-bound phases, otherwise zero
     */
    void checkpoint(Phase phase, int attempt);

    /**
     * Linearizes external cancellation and deadline semantics before parent terminal publication.
     *
     * <p>This callback is also required for idempotent parent replay, allowing a recovering worker
     * to converge an already-published parent result with its durable queue job.</p>
     */
    void prepareTerminal();

    /**
     * Returns the no-op controller used by the synchronous API.
     *
     * @return stateless no-op control
     */
    static TestSuiteStabilityExecutionControl uncontrolled() {
        return NoControl.INSTANCE;
    }

    /** Stateless no-op implementation. */
    enum NoControl implements TestSuiteStabilityExecutionControl {
        /** Shared no-op controller. */
        INSTANCE;

        @Override
        public void executionStarted(TestSuiteStabilityExecutionDescriptor execution) {
            java.util.Objects.requireNonNull(execution, "execution");
        }

        @Override
        public void checkpoint(Phase phase, int attempt) {
            java.util.Objects.requireNonNull(phase, "phase");
        }

        @Override
        public void prepareTerminal() {
            // The synchronous path has no external queue authority to linearize.
        }
    }
}
