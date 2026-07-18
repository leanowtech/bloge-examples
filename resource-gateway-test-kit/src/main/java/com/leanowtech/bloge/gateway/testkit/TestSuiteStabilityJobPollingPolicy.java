package com.leanowtech.bloge.gateway.testkit;

import java.time.Duration;

/**
 * Dual-bounded polling policy for one asynchronous suite-stability job.
 *
 * <p>Both request count and monotonic elapsed time are bounded. The ordinary interval applies
 * after a non-terminal response. A retryable query failure may provide {@code Retry-After}; that
 * hint is honored only when it fits the server-delay and remaining total bounds.</p>
 *
 * @param maximumPolls total query request bound
 * @param maximumElapsed monotonic total polling horizon
 * @param interval delay after a successful non-terminal query
 * @param maximumServerDelay largest admitted {@code Retry-After} delay
 */
public record TestSuiteStabilityJobPollingPolicy(
        int maximumPolls,
        Duration maximumElapsed,
        Duration interval,
        Duration maximumServerDelay) {

    private static final Duration MAXIMUM_POLICY_HORIZON = Duration.ofHours(24);
    private static final Duration MINIMUM_LOCAL_INTERVAL = Duration.ofMillis(1);

    /** Validates finite request, interval, and elapsed-time bounds. */
    public TestSuiteStabilityJobPollingPolicy {
        if (maximumPolls < 1 || maximumPolls > 100_000
                || !positive(maximumElapsed) || !positive(interval)
                || !positive(maximumServerDelay)
                || interval.compareTo(MINIMUM_LOCAL_INTERVAL) < 0
                || interval.compareTo(maximumElapsed) > 0
                || maximumServerDelay.compareTo(maximumElapsed) > 0
                || maximumElapsed.compareTo(MAXIMUM_POLICY_HORIZON) > 0) {
            throw new IllegalArgumentException(
                    "Stability-job polling policy must be positive, ordered, and bounded");
        }
    }

    /**
     * Returns a conservative local integration-test polling policy.
     *
     * @return at most 600 queries over ten minutes
     */
    public static TestSuiteStabilityJobPollingPolicy conservative() {
        return new TestSuiteStabilityJobPollingPolicy(
                600, Duration.ofMinutes(10), Duration.ofSeconds(1), Duration.ofMinutes(1));
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
