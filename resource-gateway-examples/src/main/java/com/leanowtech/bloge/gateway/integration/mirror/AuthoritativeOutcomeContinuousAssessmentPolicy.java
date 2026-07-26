package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Duration;
import java.util.Objects;

/**
 * Server-owned freshness, lease, and retry policy for continuous completeness projection.
 *
 * <p>{@code pollingInterval} is both the normal scheduling delay and the maximum database-source
 * freshness claim. At its half-open boundary an old assessment becomes stale before another
 * worker may revalidate it. Dependency failures use bounded exponential backoff and never extend
 * the previous freshness window.</p>
 *
 * @param leaseDuration one worker ownership interval
 * @param pollingInterval maximum source-head freshness and successful recheck delay
 * @param initialRetryDelay first retryable-failure delay
 * @param maximumRetryDelay largest retryable-failure delay
 * @param maximumConsecutiveFailures failures allowed before quarantine
 */
public record AuthoritativeOutcomeContinuousAssessmentPolicy(
        Duration leaseDuration,
        Duration pollingInterval,
        Duration initialRetryDelay,
        Duration maximumRetryDelay,
        int maximumConsecutiveFailures
) {
    /** Conservative first-generation policy. */
    public static final AuthoritativeOutcomeContinuousAssessmentPolicy DEFAULT =
            new AuthoritativeOutcomeContinuousAssessmentPolicy(
                    Duration.ofMinutes(5),
                    Duration.ofMinutes(1),
                    Duration.ofSeconds(5),
                    Duration.ofMinutes(5),
                    8);

    /** Validates finite policy bounds suitable for database coordination. */
    public AuthoritativeOutcomeContinuousAssessmentPolicy {
        leaseDuration = duration(
                leaseDuration,
                Duration.ofSeconds(1),
                Duration.ofHours(1),
                "leaseDuration");
        pollingInterval = duration(
                pollingInterval,
                Duration.ofSeconds(1),
                Duration.ofDays(1),
                "pollingInterval");
        initialRetryDelay = duration(
                initialRetryDelay,
                Duration.ofMillis(100),
                Duration.ofHours(1),
                "initialRetryDelay");
        maximumRetryDelay = duration(
                maximumRetryDelay,
                initialRetryDelay,
                Duration.ofDays(1),
                "maximumRetryDelay");
        if (maximumConsecutiveFailures < 1
                || maximumConsecutiveFailures > 1_000) {
            throw new IllegalArgumentException(
                    "maximumConsecutiveFailures is outside the supported bound");
        }
    }

    /**
     * Calculates bounded exponential backoff.
     *
     * @param consecutiveFailures one-based failure count
     * @return policy-bounded delay
     */
    public Duration retryDelay(int consecutiveFailures) {
        if (consecutiveFailures < 1) {
            throw new IllegalArgumentException(
                    "consecutiveFailures must be positive");
        }
        long initial = initialRetryDelay.toMillis();
        long maximum = maximumRetryDelay.toMillis();
        int shifts = Math.min(consecutiveFailures - 1, 62);
        long calculated;
        try {
            calculated = Math.multiplyExact(
                    initial, 1L << shifts);
        } catch (ArithmeticException overflow) {
            calculated = maximum;
        }
        return Duration.ofMillis(
                Math.min(calculated, maximum));
    }

    private static Duration duration(
            Duration value,
            Duration minimum,
            Duration maximum,
            String field) {
        Duration exact = Objects.requireNonNull(value, field);
        if (exact.compareTo(minimum) < 0
                || exact.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    field + " is outside the supported bound");
        }
        return exact;
    }
}
