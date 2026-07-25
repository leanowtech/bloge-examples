package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Duration;
import java.util.Objects;

/**
 * Server-owned queue, lease, retry, and deadline policy for durable Shadow work.
 *
 * <p>None of these controls are caller supplied. A deployment may replace the policy bean from
 * governed configuration, while every admitted job freezes the maximum-attempt value that was in
 * force at admission.</p>
 *
 * @param maximumAttempts total physical worker attempts admitted for one logical sample
 * @param leaseDuration owner/epoch lease duration
 * @param retryDelay delay before a retryable failure becomes claimable
 * @param maximumDeadlineHorizon farthest caller deadline accepted from database admission time
 */
public record ReadOnlyShadowJobPolicy(
        int maximumAttempts,
        Duration leaseDuration,
        Duration retryDelay,
        Duration maximumDeadlineHorizon
) {
    /** Conservative default suitable for local and staging control planes. */
    public static final ReadOnlyShadowJobPolicy DEFAULT =
            new ReadOnlyShadowJobPolicy(
                    3,
                    Duration.ofMinutes(2),
                    Duration.ofSeconds(10),
                    Duration.ofHours(24));

    /** Rejects retry amplification, unusable leases, and unbounded retained work. */
    public ReadOnlyShadowJobPolicy {
        leaseDuration = positive(
                leaseDuration, "leaseDuration");
        retryDelay = nonNegative(
                retryDelay, "retryDelay");
        maximumDeadlineHorizon = positive(
                maximumDeadlineHorizon,
                "maximumDeadlineHorizon");
        if (maximumAttempts < 1
                || maximumAttempts > 5
                || leaseDuration.compareTo(
                Duration.ofSeconds(5)) < 0
                || leaseDuration.compareTo(
                Duration.ofMinutes(30)) > 0
                || retryDelay.compareTo(
                Duration.ofMinutes(30)) > 0
                || maximumDeadlineHorizon.compareTo(
                Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException(
                    "read-only Shadow job policy is outside safety bounds");
        }
    }

    private static Duration positive(
            Duration value,
            String field) {
        Duration exact = Objects.requireNonNull(value, field);
        if (exact.isZero() || exact.isNegative()) {
            throw new IllegalArgumentException(
                    field + " must be positive");
        }
        return exact;
    }

    private static Duration nonNegative(
            Duration value,
            String field) {
        Duration exact = Objects.requireNonNull(value, field);
        if (exact.isNegative()) {
            throw new IllegalArgumentException(
                    field + " must not be negative");
        }
        return exact;
    }
}
