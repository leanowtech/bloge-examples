package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.Objects;

/** Aggregate-only result of one atomically committed stability-job retention page. */
public record TestSuiteStabilityJobRetentionResult(
        int jobsTombstoned,
        int tombstonesPurged,
        Instant completedAt) {

    /** Validates bounded non-negative counts and database completion time. */
    public TestSuiteStabilityJobRetentionResult {
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
        if (jobsTombstoned < 0 || tombstonesPurged < 0) {
            throw new IllegalArgumentException(
                    "Stability-job retention counts must be non-negative");
        }
    }
}
