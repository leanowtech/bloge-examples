package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Duration;
import java.util.Objects;

/**
 * Server-owned lease, polling, retry, and ageing policy for authoritative outcome reconciliation.
 *
 * <p>No connector or caller may choose these controls. A no-change poll is not a failure and uses
 * the normal polling interval. Dependency failures use bounded exponential backoff and eventually
 * quarantine the current head without converting an unknown business result into censored or
 * failed. Database time remains authoritative for every lease and scheduling decision.</p>
 *
 * @param leaseDuration one worker ownership interval
 * @param pollingInterval delay after a valid no-change connector response
 * @param initialRetryDelay first dependency-failure delay
 * @param maximumRetryDelay largest dependency-failure delay
 * @param maximumConsecutiveFailures failures allowed before quarantine
 * @param maximumPendingAge longest time after the attribution window close that polling may remain
 *                          autonomous
 */
public record AuthoritativeOutcomeInboxPolicy(
        Duration leaseDuration,
        Duration pollingInterval,
        Duration initialRetryDelay,
        Duration maximumRetryDelay,
        int maximumConsecutiveFailures,
        Duration maximumPendingAge
) {
    /** Conservative first-generation runtime policy. */
    public static final AuthoritativeOutcomeInboxPolicy DEFAULT =
            new AuthoritativeOutcomeInboxPolicy(
                    Duration.ofSeconds(30),
                    Duration.ofMinutes(5),
                    Duration.ofSeconds(5),
                    Duration.ofMinutes(5),
                    8,
                    Duration.ofDays(30));

    /** Validates finite controls suitable for a database-coordinated worker. */
    public AuthoritativeOutcomeInboxPolicy {
        leaseDuration = duration(
                leaseDuration,
                Duration.ofSeconds(1),
                Duration.ofMinutes(30),
                "leaseDuration");
        pollingInterval = duration(
                pollingInterval,
                Duration.ofSeconds(1),
                Duration.ofDays(1),
                "pollingInterval");
        initialRetryDelay = duration(
                initialRetryDelay,
                Duration.ofMillis(100),
                Duration.ofMinutes(30),
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
        maximumPendingAge = duration(
                maximumPendingAge,
                Duration.ofHours(1),
                Duration.ofDays(730),
                "maximumPendingAge");
    }

    /**
     * Calculates bounded exponential retry delay without overflowing long arithmetic.
     *
     * @param consecutiveFailures one-based failure count
     * @return policy-bounded retry delay
     */
    public Duration retryDelay(int consecutiveFailures) {
        if (consecutiveFailures < 1) {
            throw new IllegalArgumentException(
                    "consecutiveFailures must be positive");
        }
        long initial = initialRetryDelay.toMillis();
        long maximum = maximumRetryDelay.toMillis();
        int shifts = Math.min(
                consecutiveFailures - 1, 62);
        long factor = 1L << shifts;
        long calculated;
        try {
            calculated = Math.multiplyExact(initial, factor);
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
