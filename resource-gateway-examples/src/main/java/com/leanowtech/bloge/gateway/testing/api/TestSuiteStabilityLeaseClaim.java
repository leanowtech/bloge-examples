package com.leanowtech.bloge.gateway.testing.api;

/**
 * Atomic claim outcome for one scoped suite-stability idempotency identity.
 *
 * @param state acquired, already-running, already-completed, or terminally-stopped state
 * @param lease exact fence only when acquired
 * @param progress retained parent journal only when acquired
 * @param terminal retained signed result only when completed
 * @param stop retained payload-free stop only when stopped
 * @param retryAfterSeconds bounded database-clock delay only when another owner is active
 */
public record TestSuiteStabilityLeaseClaim(
        State state,
        TestSuiteStabilityExecutionLease lease,
        TestSuiteStabilityExecutionProgress progress,
        TestSuiteStabilityRunRecord terminal,
        TestSuiteStabilityExecutionStop stop,
        long retryAfterSeconds
) {
    /** Mutually exclusive claim outcomes. */
    public enum State {
        /** This invocation owns the exact live execution fence. */
        ACQUIRED,
        /** Another invocation owns an unexpired fence for the same immutable intent. */
        IN_PROGRESS,
        /** The immutable terminal response already exists and should be replayed. */
        COMPLETED,
        /** A retained cancellation, deadline, or worker-failure tombstone forbids resumption. */
        STOPPED
    }

    /** Enforces one unambiguous payload-free shape per claim state. */
    public TestSuiteStabilityLeaseClaim {
        if (state == null
                || state == State.ACQUIRED && (lease == null || progress == null || terminal != null
                || stop != null
                || retryAfterSeconds != 0)
                || state == State.IN_PROGRESS && (lease != null || progress != null || terminal != null
                || stop != null
                || retryAfterSeconds < 1 || retryAfterSeconds > 3_600)
                || state == State.COMPLETED && (lease != null || progress != null || terminal == null
                || stop != null || retryAfterSeconds != 0)
                || state == State.STOPPED && (lease != null || progress != null || terminal != null
                || stop == null || retryAfterSeconds != 0)) {
            throw new IllegalArgumentException("Suite-stability lease claim shape is invalid");
        }
    }

    /**
     * @param lease acquired exact fence
     * @param progress initialized or resumed durable parent journal
     * @return acquired claim
     */
    public static TestSuiteStabilityLeaseClaim acquired(
            TestSuiteStabilityExecutionLease lease,
            TestSuiteStabilityExecutionProgress progress) {
        return new TestSuiteStabilityLeaseClaim(
                State.ACQUIRED, lease, progress, null, null, 0);
    }

    /**
     * @param retryAfterSeconds bounded retry delay
     * @return active-owner observation
     */
    public static TestSuiteStabilityLeaseClaim inProgress(long retryAfterSeconds) {
        return new TestSuiteStabilityLeaseClaim(
                State.IN_PROGRESS, null, null, null, null, retryAfterSeconds);
    }

    /**
     * @param terminal retained signed result
     * @return completed claim
     */
    public static TestSuiteStabilityLeaseClaim completed(
            TestSuiteStabilityRunRecord terminal) {
        return new TestSuiteStabilityLeaseClaim(
                State.COMPLETED, null, null, terminal, null, 0);
    }

    /**
     * @param stop retained terminal stop tombstone
     * @return stopped claim
     */
    public static TestSuiteStabilityLeaseClaim stopped(
            TestSuiteStabilityExecutionStop stop) {
        return new TestSuiteStabilityLeaseClaim(
                State.STOPPED, null, null, null, stop, 0);
    }
}
