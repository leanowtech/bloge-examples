package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;

/**
 * Atomic result of one non-blocking fair queue claim.
 *
 * @param outcome acquired work or bounded no-work observation
 * @param observedAt database-authority linearization time
 * @param job integrity-verified job only for {@link Outcome#ACQUIRED}
 * @param lease exact worker fence only for {@link Outcome#ACQUIRED}
 * @param effectivePriority aged priority selected inside the chosen tenant
 */
public record TestSuiteStabilityJobClaim(
        Outcome outcome,
        Instant observedAt,
        TestSuiteStabilityJobRecord job,
        TestSuiteStabilityJobLease lease,
        int effectivePriority) {

    /** Claim outcomes deliberately do not disclose queue contents. */
    public enum Outcome {
        ACQUIRED,
        NO_WORK
    }

    /** Enforces mutually exclusive acquired and no-work shapes. */
    public TestSuiteStabilityJobClaim {
        outcome = java.util.Objects.requireNonNull(outcome, "outcome");
        observedAt = java.util.Objects.requireNonNull(observedAt, "observedAt");
        if ((outcome == Outcome.ACQUIRED) != (job != null && lease != null)
                || effectivePriority < 0 || effectivePriority > 2) {
            throw new IllegalArgumentException("Invalid suite-stability job claim");
        }
    }

    /** @return an acquired job and exact lease */
    public static TestSuiteStabilityJobClaim acquired(
            Instant observedAt,
            TestSuiteStabilityJobRecord job,
            TestSuiteStabilityJobLease lease,
            int effectivePriority) {
        return new TestSuiteStabilityJobClaim(
                Outcome.ACQUIRED, observedAt, job, lease, effectivePriority);
    }

    /** @return a bounded no-work observation */
    public static TestSuiteStabilityJobClaim noWork(Instant observedAt) {
        return new TestSuiteStabilityJobClaim(Outcome.NO_WORK, observedAt, null, null, 0);
    }
}
