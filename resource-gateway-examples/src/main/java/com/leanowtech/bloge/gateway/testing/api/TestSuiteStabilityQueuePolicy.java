package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Duration;
import java.util.Objects;

/**
 * Versioned server policy for the durable suite-stability parent queue.
 *
 * <p>The repository persists the fingerprint as the active policy for each environment. Replicas
 * with a different policy fail closed while work is retained; an empty queue may advance to a new
 * generation. This prevents whichever replica happens to claim next from silently changing
 * capacity or fairness semantics.</p>
 *
 * @param generation monotonically increasing deployment policy generation
 * @param maximumQueued maximum non-terminal jobs in one environment
 * @param maximumQueuedPerTenant maximum non-terminal jobs for one tenant and environment
 * @param maximumRunning maximum live claimed jobs in one environment
 * @param maximumRunningPerTenant maximum live claimed jobs for one tenant and environment
 * @param leaseDuration database-clock worker lease duration
 * @param agingInterval wait interval that raises one effective priority level
 * @param initialRetryDelay first infrastructure retry delay
 * @param maximumRetryDelay maximum infrastructure retry delay
 * @param maximumRetries maximum retry transitions before a job fails terminally
 * @param maximumDeadlineHorizon maximum accepted deadline distance from database submission time
 * @param terminalRetention terminal job retention
 */
public record TestSuiteStabilityQueuePolicy(
        long generation,
        int maximumQueued,
        int maximumQueuedPerTenant,
        int maximumRunning,
        int maximumRunningPerTenant,
        Duration leaseDuration,
        Duration agingInterval,
        Duration initialRetryDelay,
        Duration maximumRetryDelay,
        int maximumRetries,
        Duration maximumDeadlineHorizon,
        Duration terminalRetention) {

    private static final int MAXIMUM_QUEUE = 100_000;

    /** Validates a finite, internally consistent queue policy. */
    public TestSuiteStabilityQueuePolicy {
        leaseDuration = wholeSeconds(leaseDuration, "leaseDuration", 5, 3_600);
        agingInterval = wholeSeconds(agingInterval, "agingInterval", 1, 86_400);
        initialRetryDelay = wholeSeconds(initialRetryDelay, "initialRetryDelay", 1, 3_600);
        maximumRetryDelay = wholeSeconds(maximumRetryDelay, "maximumRetryDelay", 1, 86_400);
        maximumDeadlineHorizon = wholeSeconds(
                maximumDeadlineHorizon, "maximumDeadlineHorizon", 5, 30L * 86_400L);
        terminalRetention = wholeSeconds(terminalRetention, "terminalRetention", 3_600,
                3650L * 86_400L);
        if (generation <= 0
                || maximumQueued <= 0 || maximumQueued > MAXIMUM_QUEUE
                || maximumQueuedPerTenant <= 0 || maximumQueuedPerTenant > maximumQueued
                || maximumRunning <= 0 || maximumRunning > maximumQueued
                || maximumRunningPerTenant <= 0
                || maximumRunningPerTenant > maximumRunning
                || maximumRunningPerTenant > maximumQueuedPerTenant
                || initialRetryDelay.compareTo(maximumRetryDelay) > 0
                || maximumRetries < 0 || maximumRetries > 100) {
            throw new IllegalArgumentException("Invalid suite-stability queue policy");
        }
    }

    /**
     * Returns a credential-free canonical identity for cross-replica policy convergence.
     *
     * @return SHA-256 policy fingerprint
     */
    public String fingerprint() {
        return ProtocolFingerprint.ofText(String.join("|",
                "bloge.testSuiteStabilityQueuePolicy.v1",
                Long.toString(generation),
                Integer.toString(maximumQueued),
                Integer.toString(maximumQueuedPerTenant),
                Integer.toString(maximumRunning),
                Integer.toString(maximumRunningPerTenant),
                Long.toString(leaseDuration.toSeconds()),
                Long.toString(agingInterval.toSeconds()),
                Long.toString(initialRetryDelay.toSeconds()),
                Long.toString(maximumRetryDelay.toSeconds()),
                Integer.toString(maximumRetries),
                Long.toString(maximumDeadlineHorizon.toSeconds()),
                Long.toString(terminalRetention.toSeconds())));
    }

    /**
     * Computes deterministic bounded exponential retry delay.
     *
     * @param retryNumber one-based retry transition number
     * @return delay capped by {@link #maximumRetryDelay()}
     */
    public Duration retryDelay(int retryNumber) {
        if (retryNumber <= 0) {
            throw new IllegalArgumentException("retryNumber must be positive");
        }
        long initial = initialRetryDelay.toSeconds();
        int exponent = Math.min(30, retryNumber - 1);
        long multiplier = 1L << exponent;
        long candidate;
        try {
            candidate = Math.multiplyExact(initial, multiplier);
        } catch (ArithmeticException overflow) {
            candidate = Long.MAX_VALUE;
        }
        return Duration.ofSeconds(Math.min(maximumRetryDelay.toSeconds(), candidate));
    }

    private static Duration wholeSeconds(
            Duration value, String name, long minimum, long maximum) {
        Duration result = Objects.requireNonNull(value, name);
        if (result.toMillis() % 1_000 != 0
                || result.toSeconds() < minimum || result.toSeconds() > maximum) {
            throw new IllegalArgumentException(name + " must be whole seconds between "
                    + minimum + " and " + maximum);
        }
        return result;
    }
}
