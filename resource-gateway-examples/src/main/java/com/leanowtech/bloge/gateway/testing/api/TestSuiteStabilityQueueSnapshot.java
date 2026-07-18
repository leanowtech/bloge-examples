package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/**
 * Bounded-cardinality payload-free queue observation.
 *
 * @param observedAt database observation time
 * @param totals complete status totals for one environment
 * @param oldestQueuedAt oldest currently eligible or delayed queued job, otherwise {@code null}
 * @param expiredLiveLeases number of running rows whose worker lease has expired
 * @param distinctQueuedTenants number of tenants represented by queued jobs
 */
public record TestSuiteStabilityQueueSnapshot(
        Instant observedAt,
        Map<TestSuiteStabilityJobRecord.Status, Long> totals,
        Instant oldestQueuedAt,
        long expiredLiveLeases,
        long distinctQueuedTenants) {

    /** Requires a complete non-negative closed-vocabulary observation. */
    public TestSuiteStabilityQueueSnapshot {
        observedAt = java.util.Objects.requireNonNull(observedAt, "observedAt");
        EnumMap<TestSuiteStabilityJobRecord.Status, Long> normalized =
                new EnumMap<>(TestSuiteStabilityJobRecord.Status.class);
        normalized.putAll(totals == null ? Map.of() : totals);
        for (TestSuiteStabilityJobRecord.Status status
                : TestSuiteStabilityJobRecord.Status.values()) {
            normalized.putIfAbsent(status, 0L);
        }
        if (normalized.values().stream().anyMatch(value -> value == null || value < 0)
                || expiredLiveLeases < 0 || distinctQueuedTenants < 0
                || (normalized.get(TestSuiteStabilityJobRecord.Status.QUEUED) == 0)
                != (oldestQueuedAt == null)) {
            throw new IllegalArgumentException("Invalid suite-stability queue snapshot");
        }
        totals = Map.copyOf(normalized);
    }
}
