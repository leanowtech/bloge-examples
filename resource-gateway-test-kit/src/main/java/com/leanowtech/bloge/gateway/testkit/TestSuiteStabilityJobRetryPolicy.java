package com.leanowtech.bloge.gateway.testkit;

import java.time.Duration;

/**
 * Bounded retry policy for an idempotent asynchronous stability-job submission.
 *
 * <p>The first HTTP request counts as one attempt. Only server-declared retryable {@code 429} or
 * {@code 503} responses are retried. A valid {@code Retry-After} value takes precedence over local
 * exponential delay, but a delay exceeding either bound stops retry rather than sending early.</p>
 *
 * @param maximumAttempts total HTTP attempt bound, including the first request
 * @param initialDelay first local delay when the server supplies no retry hint
 * @param maximumDelay largest admitted local or server-provided delay
 * @param maximumElapsed monotonic total retry horizon
 */
public record TestSuiteStabilityJobRetryPolicy(
        int maximumAttempts,
        Duration initialDelay,
        Duration maximumDelay,
        Duration maximumElapsed) {

    private static final Duration MAXIMUM_POLICY_HORIZON = Duration.ofHours(24);
    private static final Duration MINIMUM_LOCAL_DELAY = Duration.ofMillis(1);

    /** Validates finite retry bounds. */
    public TestSuiteStabilityJobRetryPolicy {
        if (maximumAttempts < 1 || maximumAttempts > 100
                || !positive(initialDelay) || !positive(maximumDelay)
                || !positive(maximumElapsed)
                || initialDelay.compareTo(MINIMUM_LOCAL_DELAY) < 0
                || initialDelay.compareTo(maximumDelay) > 0
                || maximumDelay.compareTo(maximumElapsed) > 0
                || maximumElapsed.compareTo(MAXIMUM_POLICY_HORIZON) > 0) {
            throw new IllegalArgumentException(
                    "Stability-job retry policy must be positive, ordered, and bounded");
        }
    }

    /**
     * Returns a conservative default for local CI and integration-test callers.
     *
     * @return five attempts over at most two minutes
     */
    public static TestSuiteStabilityJobRetryPolicy conservative() {
        return new TestSuiteStabilityJobRetryPolicy(
                5, Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofMinutes(2));
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
