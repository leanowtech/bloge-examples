package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableWorkerQuarantineControlPlane;
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

/** Fixed-cardinality operational telemetry for worker-quarantine retention. */
public final class DurableWorkerQuarantineRetentionTelemetry {

    private static final String PREFIX =
            "resource.gateway.test.runtime.worker.candidate.quarantines.retention.";

    /** Closed outcome vocabulary for scheduled retention attempts. */
    public enum Result {
        COMPLETED,
        LEASE_BUSY,
        FAILED
    }

    private final boolean enabled;
    private final Map<Result, Counter> attempts;
    private final Timer duration;
    private final AtomicLong totalTombstoned;
    private final AtomicLong totalTombstonesPurged;
    private final AtomicLong totalHistoryPurged;
    private final AtomicLong tombstoneRecords;
    private final AtomicLong lastSuccessEpochSeconds;

    /**
     * Registers a closed result tag and aggregate-only gauges with the selected registry.
     *
     * @param registry deployment-selected Micrometer registry
     */
    public DurableWorkerQuarantineRetentionTelemetry(MeterRegistry registry) {
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
        totalTombstoned = gauge(meters, "tombstoned.total");
        totalTombstonesPurged = gauge(meters, "tombstones.purged.total");
        totalHistoryPurged = gauge(meters, "history.purged.total");
        tombstoneRecords = gauge(meters, "tombstones.records");
        lastSuccessEpochSeconds = gauge(meters, "last.success.epoch");
    }

    private DurableWorkerQuarantineRetentionTelemetry() {
        enabled = false;
        attempts = Map.of();
        duration = null;
        totalTombstoned = new AtomicLong();
        totalTombstonesPurged = new AtomicLong();
        totalHistoryPurged = new AtomicLong();
        tombstoneRecords = new AtomicLong();
        lastSuccessEpochSeconds = new AtomicLong();
    }

    /** @return disabled adapter for focused scheduler tests */
    public static DurableWorkerQuarantineRetentionTelemetry noop() {
        return new DurableWorkerQuarantineRetentionTelemetry();
    }

    /** Records one completed or lease-busy attempt and its wall-clock duration. */
    public void record(
            DatabaseDurableWorkerQuarantineControlPlane.RetentionAttempt attempt,
            Duration elapsed) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(elapsed, "elapsed");
        if (!enabled) {
            return;
        }
        Result result = attempt.status()
                == DatabaseDurableWorkerQuarantineControlPlane.RetentionStatus.COMPLETED
                ? Result.COMPLETED : Result.LEASE_BUSY;
        attempts.get(result).increment();
        duration.record(Math.max(0L, elapsed.toNanos()), TimeUnit.NANOSECONDS);
    }

    /** Records one failed, fully rolled-back attempt. */
    public void recordFailure(Duration elapsed) {
        Objects.requireNonNull(elapsed, "elapsed");
        if (enabled) {
            attempts.get(Result.FAILED).increment();
            duration.record(Math.max(0L, elapsed.toNanos()), TimeUnit.NANOSECONDS);
        }
    }

    /** Replaces aggregate gauges from one transactionally consistent database snapshot. */
    public void refresh(DatabaseDurableWorkerQuarantineControlPlane.RetentionSnapshot snapshot) {
        DatabaseDurableWorkerQuarantineControlPlane.RetentionSnapshot safe =
                Objects.requireNonNull(snapshot, "snapshot");
        totalTombstoned.set(safe.totalTombstoned());
        totalTombstonesPurged.set(safe.totalTombstonesPurged());
        totalHistoryPurged.set(safe.totalHistoryPurged());
        tombstoneRecords.set(safe.tombstoneRecords());
        lastSuccessEpochSeconds.set(
                safe.lastSuccessAt() == null ? 0L : safe.lastSuccessAt().getEpochSecond());
    }

    private static AtomicLong gauge(MeterRegistry registry, String suffix) {
        AtomicLong value = new AtomicLong();
        Gauge.builder(PREFIX + suffix, value, AtomicLong::doubleValue).register(registry);
        return value;
    }
}
