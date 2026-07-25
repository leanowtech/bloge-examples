package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Duration;
import java.util.Objects;

/**
 * Database-authoritative retry and lease bounds for asynchronous batch evidence finalization.
 *
 * @param generation policy generation bound to the durable outbox
 * @param leaseDuration finalizer claim lifetime
 * @param initialRetryBackoff first failed-attempt delay
 * @param maximumRetryBackoff capped exponential delay
 * @param maximumAutomaticAttempts durable attempt budget before quarantine
 */
public record ScenarioRehearsalBatchFinalizationPolicy(
        long generation,
        Duration leaseDuration,
        Duration initialRetryBackoff,
        Duration maximumRetryBackoff,
        int maximumAutomaticAttempts
) {
    /** Conservative defaults for one remote KMS call and terminal database commit. */
    public static ScenarioRehearsalBatchFinalizationPolicy defaults() {
        return new ScenarioRehearsalBatchFinalizationPolicy(
                1,
                Duration.ofMinutes(2),
                Duration.ofSeconds(5),
                Duration.ofMinutes(5),
                20);
    }

    /** Enforces bounded retry pressure and a finite unattended failure budget. */
    public ScenarioRehearsalBatchFinalizationPolicy {
        leaseDuration = bounded(
                leaseDuration,
                "leaseDuration",
                Duration.ofSeconds(5),
                Duration.ofHours(1));
        initialRetryBackoff = bounded(
                initialRetryBackoff,
                "initialRetryBackoff",
                Duration.ofMillis(100),
                Duration.ofHours(1));
        maximumRetryBackoff = bounded(
                maximumRetryBackoff,
                "maximumRetryBackoff",
                initialRetryBackoff,
                Duration.ofDays(1));
        if (generation < 1
                || maximumAutomaticAttempts < 1
                || maximumAutomaticAttempts > 10_000) {
            throw new IllegalArgumentException(
                    "Scenario batch finalization policy is invalid");
        }
    }

    /** Returns an overflow-safe capped exponential delay after one failed attempt. */
    public Duration retryBackoff(int attemptCount) {
        if (attemptCount < 1) {
            throw new IllegalArgumentException(
                    "Scenario batch finalization attempt count must be positive");
        }
        Duration delay = initialRetryBackoff;
        for (int exponent = 1;
             exponent < attemptCount
                     && delay.compareTo(maximumRetryBackoff) < 0;
             exponent++) {
            delay = delay.compareTo(
                    maximumRetryBackoff.dividedBy(2)) > 0
                    ? maximumRetryBackoff
                    : delay.multipliedBy(2);
        }
        return delay.compareTo(maximumRetryBackoff) > 0
                ? maximumRetryBackoff : delay;
    }

    private static Duration bounded(
            Duration value,
            String field,
            Duration minimum,
            Duration maximum) {
        Duration result = Objects.requireNonNull(value, field);
        if (result.compareTo(minimum) < 0
                || result.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    field + " is outside the supported finalization bound");
        }
        return result;
    }
}
