package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.Objects;

/**
 * Database-time snapshot of durable parent progress and current ownership liveness.
 *
 * @param progress exact retained parent journal
 * @param liveOwner whether a matching lease is live at {@code observedAt}
 * @param observedAt database-authoritative observation time
 */
public record TestSuiteStabilityProgressSnapshot(
        TestSuiteStabilityExecutionProgress progress,
        boolean liveOwner,
        Instant observedAt
) {
    /** Rejects expired or temporally impossible projections. */
    public TestSuiteStabilityProgressSnapshot {
        progress = Objects.requireNonNull(progress, "progress");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        if (!progress.expiresAt().isAfter(observedAt)
                || observedAt.isBefore(progress.createdAt())) {
            throw new IllegalArgumentException(
                    "Suite-stability progress snapshot must be retained at database time");
        }
    }
}
