package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;

/**
 * Version-fenced snapshot of a RUNNING suite whose owner lease has expired.
 *
 * @param record latest durable aggregate checkpoint
 * @param checkpointVersion database-owned optimistic concurrency fence
 * @param leaseOwner runtime instance that last owned the run
 * @param leaseExpiresAt observed expired lease deadline
 */
public record AbandonedTestSuiteRun(
        TestSuiteRunRecord record,
        long checkpointVersion,
        String leaseOwner,
        Instant leaseExpiresAt
) {
    /** Requires enough identity to perform a compare-and-set reconciliation. */
    public AbandonedTestSuiteRun {
        if (record == null || checkpointVersion < 0 || leaseExpiresAt == null) {
            throw new IllegalArgumentException("Complete abandoned suite-run snapshot is required");
        }
        leaseOwner = leaseOwner == null ? "" : leaseOwner.trim();
    }
}
