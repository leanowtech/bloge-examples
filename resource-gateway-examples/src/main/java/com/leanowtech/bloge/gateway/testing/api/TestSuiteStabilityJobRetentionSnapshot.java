package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.Objects;

/**
 * Aggregate-only database snapshot of the stability-job retention lifecycle.
 *
 * @param leaseOwner current maintenance replica or empty while idle
 * @param leaseEpoch monotonic ownership generation
 * @param leaseUntil database-clock ownership deadline
 * @param revision monotonic integrity-state revision
 * @param totalJobsTombstoned cumulative detailed terminal jobs erased
 * @param totalTombstonesPurged cumulative expired request reservations erased
 * @param detailedJobRecords current detailed queue-record count
 * @param tombstoneRecords current keyed request-tombstone count
 * @param overdueJobRecords expired terminal jobs waiting for retention
 * @param expiredTombstoneRecords expired tombstones waiting for purge
 * @param oldestOverdueJobExpiresAt expiry of the oldest terminal backlog row
 * @param oldestExpiredTombstoneExpiresAt expiry of the oldest tombstone backlog row
 * @param lastSuccessAt last successfully committed page or {@code null}
 * @param observedAt database-authority snapshot time
 */
public record TestSuiteStabilityJobRetentionSnapshot(
        String leaseOwner,
        long leaseEpoch,
        Instant leaseUntil,
        long revision,
        long totalJobsTombstoned,
        long totalTombstonesPurged,
        long detailedJobRecords,
        long tombstoneRecords,
        long overdueJobRecords,
        long expiredTombstoneRecords,
        Instant oldestOverdueJobExpiresAt,
        Instant oldestExpiredTombstoneExpiresAt,
        Instant lastSuccessAt,
        Instant observedAt) {

    /** Validates exact count/time correspondence and database-clock causality. */
    public TestSuiteStabilityJobRetentionSnapshot {
        leaseOwner = leaseOwner == null ? "" : leaseOwner.trim();
        leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        if (leaseEpoch < 0 || revision < 0 || totalJobsTombstoned < 0
                || totalTombstonesPurged < 0 || detailedJobRecords < 0
                || tombstoneRecords < 0 || overdueJobRecords < 0
                || expiredTombstoneRecords < 0) {
            throw new IllegalArgumentException(
                    "Stability-job retention snapshot counters cannot be negative");
        }
        if ((overdueJobRecords == 0) != (oldestOverdueJobExpiresAt == null)
                || (expiredTombstoneRecords == 0)
                != (oldestExpiredTombstoneExpiresAt == null)) {
            throw new IllegalArgumentException(
                    "Stability-job retention backlog counts require exact oldest timestamps");
        }
        if ((oldestOverdueJobExpiresAt != null
                && oldestOverdueJobExpiresAt.isAfter(observedAt))
                || (oldestExpiredTombstoneExpiresAt != null
                && oldestExpiredTombstoneExpiresAt.isAfter(observedAt))
                || (lastSuccessAt != null && lastSuccessAt.isAfter(observedAt))) {
            throw new IllegalArgumentException(
                    "Stability-job retention times cannot be after observation time");
        }
    }
}
