package com.leanowtech.bloge.gateway.testing.api;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Fixed-cardinality operational telemetry for durable recovery-sequence retention. */
public final class DurableRecoverySequenceRetentionTelemetry {

    private static final String PREFIX =
            "resource.gateway.test.runtime.durable.recovery.sequences.retention.";

    /** Closed result vocabulary used as the sole attempt tag. */
    public enum Result {
        COMPLETED,
        LEASE_BUSY,
        FAILED
    }

    private final boolean enabled;
    private final Map<Result, Counter> attempts;
    private final Timer duration;
    private final AtomicLong totalSequencesTombstoned;
    private final AtomicLong totalRecoveryStepsPurged;
    private final AtomicLong totalOwnerClaimsPurged;
    private final AtomicLong totalHeartbeatsPurged;
    private final AtomicLong totalTombstonesPurged;
    private final AtomicLong activeSequenceRecords;
    private final AtomicLong tombstoneRecords;
    private final AtomicLong lastSuccessEpochSeconds;
    private final AtomicLong overdueSequenceRecords;
    private final AtomicLong expiredTombstoneRecords;
    private final AtomicLong retentionSuccessAgeSeconds;
    private final AtomicLong oldestOverdueSequenceAgeSeconds;
    private final AtomicLong oldestExpiredTombstoneAgeSeconds;
    private final AtomicLong health;

    /**
     * Registers closed-result counters and aggregate-only lifecycle gauges.
     *
     * @param registry deployment-selected Micrometer registry
     */
    public DurableRecoverySequenceRetentionTelemetry(MeterRegistry registry) {
        MeterRegistry meters = Objects.requireNonNull(registry, "registry");
        enabled = true;
        EnumMap<Result, Counter> registered = new EnumMap<>(Result.class);
        for (Result result : Result.values()) {
            registered.put(result, Counter.builder(PREFIX + "attempts")
                    .tag("result", result.name().toLowerCase(java.util.Locale.ROOT))
                    .register(meters));
        }
        attempts = Map.copyOf(registered);
        duration = Timer.builder(PREFIX + "duration").register(meters);
        totalSequencesTombstoned = gauge(meters, "sequences.tombstoned.total");
        totalRecoveryStepsPurged = gauge(meters, "steps.purged.total");
        totalOwnerClaimsPurged = gauge(meters, "claims.purged.total");
        totalHeartbeatsPurged = gauge(meters, "heartbeats.purged.total");
        totalTombstonesPurged = gauge(meters, "tombstones.purged.total");
        activeSequenceRecords = gauge(meters, "sequences.records");
        tombstoneRecords = gauge(meters, "tombstones.records");
        lastSuccessEpochSeconds = gauge(meters, "last.success.epoch");
        overdueSequenceRecords = gauge(meters, "sequences.overdue");
        expiredTombstoneRecords = gauge(meters, "tombstones.expired");
        retentionSuccessAgeSeconds = gauge(meters, "last.success.age");
        oldestOverdueSequenceAgeSeconds = gauge(
                meters, "sequences.overdue.oldest.age");
        oldestExpiredTombstoneAgeSeconds = gauge(
                meters, "tombstones.expired.oldest.age");
        health = gauge(meters, "health");
        retentionSuccessAgeSeconds.set(-1);
        oldestOverdueSequenceAgeSeconds.set(-1);
        oldestExpiredTombstoneAgeSeconds.set(-1);
    }

    private DurableRecoverySequenceRetentionTelemetry() {
        enabled = false;
        attempts = Map.of();
        duration = null;
        totalSequencesTombstoned = new AtomicLong();
        totalRecoveryStepsPurged = new AtomicLong();
        totalOwnerClaimsPurged = new AtomicLong();
        totalHeartbeatsPurged = new AtomicLong();
        totalTombstonesPurged = new AtomicLong();
        activeSequenceRecords = new AtomicLong();
        tombstoneRecords = new AtomicLong();
        lastSuccessEpochSeconds = new AtomicLong();
        overdueSequenceRecords = new AtomicLong();
        expiredTombstoneRecords = new AtomicLong();
        retentionSuccessAgeSeconds = new AtomicLong(-1);
        oldestOverdueSequenceAgeSeconds = new AtomicLong(-1);
        oldestExpiredTombstoneAgeSeconds = new AtomicLong(-1);
        health = new AtomicLong();
    }

    /** @return disabled adapter for focused scheduler tests */
    public static DurableRecoverySequenceRetentionTelemetry noop() {
        return new DurableRecoverySequenceRetentionTelemetry();
    }

    /** Records one completed or lease-busy attempt and its wall-clock duration. */
    public void record(
            DurableTestExecutionCheckpointRepository.RecoverySequenceRetentionAttempt attempt,
            Duration elapsed) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(elapsed, "elapsed");
        if (!enabled) {
            return;
        }
        Result result = attempt.status()
                == DurableTestExecutionCheckpointRepository
                .RecoverySequenceRetentionStatus.COMPLETED
                ? Result.COMPLETED : Result.LEASE_BUSY;
        attempts.get(result).increment();
        duration.record(Math.max(0L, elapsed.toNanos()), TimeUnit.NANOSECONDS);
    }

    /** Records one failed and fully rolled-back retention attempt. */
    public void recordFailure(Duration elapsed) {
        Objects.requireNonNull(elapsed, "elapsed");
        if (enabled) {
            attempts.get(Result.FAILED).increment();
            duration.record(Math.max(0L, elapsed.toNanos()), TimeUnit.NANOSECONDS);
        }
    }

    /** Replaces aggregate gauges from one transactionally consistent database snapshot. */
    public void refresh(
            DurableTestExecutionCheckpointRepository.RecoverySequenceRetentionSnapshot snapshot) {
        var safe = Objects.requireNonNull(snapshot, "snapshot");
        totalSequencesTombstoned.set(safe.totalSequencesTombstoned());
        totalRecoveryStepsPurged.set(safe.totalRecoveryStepsPurged());
        totalOwnerClaimsPurged.set(safe.totalOwnerClaimsPurged());
        totalHeartbeatsPurged.set(safe.totalHeartbeatsPurged());
        totalTombstonesPurged.set(safe.totalTombstonesPurged());
        activeSequenceRecords.set(safe.activeSequenceRecords());
        tombstoneRecords.set(safe.tombstoneRecords());
        lastSuccessEpochSeconds.set(
                safe.lastSuccessAt() == null ? 0L : safe.lastSuccessAt().getEpochSecond());
        overdueSequenceRecords.set(safe.overdueSequenceRecords());
        expiredTombstoneRecords.set(safe.expiredTombstoneRecords());
    }

    /** Publishes one assessed aggregate SLO view without adding identity-derived labels. */
    public void observeSlo(
            DurableTestExecutionCheckpointRepository.RecoverySequenceRetentionSnapshot snapshot,
            DurableRecoverySequenceRetentionSloMonitor.State state,
            Duration retentionSuccessAge,
            Duration oldestOverdueSequenceAge,
            Duration oldestExpiredTombstoneAge) {
        Objects.requireNonNull(state, "state");
        refresh(snapshot);
        if (!enabled) {
            return;
        }
        retentionSuccessAgeSeconds.set(secondsOrUnknown(retentionSuccessAge));
        oldestOverdueSequenceAgeSeconds.set(secondsOrUnknown(oldestOverdueSequenceAge));
        oldestExpiredTombstoneAgeSeconds.set(secondsOrUnknown(oldestExpiredTombstoneAge));
        health.set(switch (state) {
            case HEALTHY -> 1;
            case INITIALIZING -> 0;
            case SLO_VIOLATED -> -1;
            case STORE_UNAVAILABLE -> -2;
        });
    }

    /** Marks aggregate SLO gauges unavailable without retaining an exception message. */
    public void observeStoreUnavailable() {
        if (enabled) {
            health.set(-2);
            retentionSuccessAgeSeconds.set(-1);
            oldestOverdueSequenceAgeSeconds.set(-1);
            oldestExpiredTombstoneAgeSeconds.set(-1);
        }
    }

    private static long secondsOrUnknown(Duration value) {
        return value == null ? -1 : Math.max(0L, value.toSeconds());
    }

    private static AtomicLong gauge(MeterRegistry registry, String suffix) {
        AtomicLong value = new AtomicLong();
        Gauge.builder(PREFIX + suffix, value, AtomicLong::doubleValue).register(registry);
        return value;
    }
}
