package com.leanowtech.bloge.graphengine.service;

import java.time.Duration;

/**
 * Process-level operations policy used by the graph-engine service to turn
 * control-plane observations into SLO health and action severity.
 *
 * @param deadLetterAgeWarning warning threshold for the oldest sampled dead letter
 * @param deadLetterAgeCritical critical threshold for the oldest sampled dead letter
 * @param suspendedInstanceAgeWarning warning threshold for the oldest sampled suspended instance
 * @param suspendedInstanceAgeCritical critical threshold for the oldest sampled suspended instance
 */
public record GraphOperationsPolicy(
        Duration deadLetterAgeWarning,
        Duration deadLetterAgeCritical,
        Duration suspendedInstanceAgeWarning,
        Duration suspendedInstanceAgeCritical
) {
    public static final Duration DEFAULT_DEAD_LETTER_AGE_WARNING = Duration.ofMinutes(5);
    public static final Duration DEFAULT_DEAD_LETTER_AGE_CRITICAL = Duration.ofMinutes(30);
    public static final Duration DEFAULT_SUSPENDED_INSTANCE_AGE_WARNING = Duration.ofMinutes(15);
    public static final Duration DEFAULT_SUSPENDED_INSTANCE_AGE_CRITICAL = Duration.ofHours(2);

    public GraphOperationsPolicy {
        deadLetterAgeWarning = positiveOrDefault(deadLetterAgeWarning, DEFAULT_DEAD_LETTER_AGE_WARNING);
        deadLetterAgeCritical = positiveOrDefault(deadLetterAgeCritical, DEFAULT_DEAD_LETTER_AGE_CRITICAL);
        suspendedInstanceAgeWarning = positiveOrDefault(
                suspendedInstanceAgeWarning,
                DEFAULT_SUSPENDED_INSTANCE_AGE_WARNING
        );
        suspendedInstanceAgeCritical = positiveOrDefault(
                suspendedInstanceAgeCritical,
                DEFAULT_SUSPENDED_INSTANCE_AGE_CRITICAL
        );
        requireOrdered(deadLetterAgeWarning, deadLetterAgeCritical, "deadLetterAge");
        requireOrdered(suspendedInstanceAgeWarning, suspendedInstanceAgeCritical, "suspendedInstanceAge");
    }

    /**
     * Returns the default operations policy.
     */
    public static GraphOperationsPolicy defaultPolicy() {
        return new GraphOperationsPolicy(
                DEFAULT_DEAD_LETTER_AGE_WARNING,
                DEFAULT_DEAD_LETTER_AGE_CRITICAL,
                DEFAULT_SUSPENDED_INSTANCE_AGE_WARNING,
                DEFAULT_SUSPENDED_INSTANCE_AGE_CRITICAL
        );
    }

    public int deadLetterAgeWarningSeconds() {
        return seconds(deadLetterAgeWarning);
    }

    public int deadLetterAgeCriticalSeconds() {
        return seconds(deadLetterAgeCritical);
    }

    public int suspendedInstanceAgeWarningSeconds() {
        return seconds(suspendedInstanceAgeWarning);
    }

    public int suspendedInstanceAgeCriticalSeconds() {
        return seconds(suspendedInstanceAgeCritical);
    }

    private static Duration positiveOrDefault(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private static void requireOrdered(Duration warning, Duration critical, String label) {
        if (warning.compareTo(critical) > 0) {
            throw new IllegalArgumentException(label + " warning threshold must not exceed critical threshold");
        }
    }

    private static int seconds(Duration duration) {
        long seconds = duration.toSeconds();
        return seconds > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) seconds;
    }
}
