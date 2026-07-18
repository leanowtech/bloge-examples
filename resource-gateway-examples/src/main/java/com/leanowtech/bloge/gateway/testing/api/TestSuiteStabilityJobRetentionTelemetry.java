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

/** Fixed-cardinality operational telemetry for suite-stability job retention. */
public final class TestSuiteStabilityJobRetentionTelemetry {

    private static final String PREFIX =
            "resource.gateway.test.runtime.suite.stability.jobs.retention.";

    /** Closed attempt vocabulary used as the sole metric tag. */
    public enum Result {
        COMPLETED,
        LEASE_BUSY,
        FAILED
    }

    private final boolean enabled;
    private final Map<Result, Counter> attempts;
    private final Timer duration;
    private final AtomicLong totalJobsTombstoned;
    private final AtomicLong totalTombstonesPurged;
    private final AtomicLong detailedJobRecords;
    private final AtomicLong tombstoneRecords;
    private final AtomicLong overdueJobRecords;
    private final AtomicLong expiredTombstoneRecords;
    private final AtomicLong lastSuccessEpochSeconds;
    private final AtomicLong retentionSuccessAgeSeconds;
    private final AtomicLong oldestOverdueJobAgeSeconds;
    private final AtomicLong oldestExpiredTombstoneAgeSeconds;
    private final AtomicLong health;

    /** Registers aggregate gauges and counters with one closed result tag. */
    public TestSuiteStabilityJobRetentionTelemetry(MeterRegistry registry) {
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
        totalJobsTombstoned = gauge(meters, "jobs.tombstoned.total");
        totalTombstonesPurged = gauge(meters, "tombstones.purged.total");
        detailedJobRecords = gauge(meters, "jobs.records");
        tombstoneRecords = gauge(meters, "tombstones.records");
        overdueJobRecords = gauge(meters, "jobs.overdue");
        expiredTombstoneRecords = gauge(meters, "tombstones.expired");
        lastSuccessEpochSeconds = gauge(meters, "last.success.epoch");
        retentionSuccessAgeSeconds = unknownGauge(meters, "last.success.age");
        oldestOverdueJobAgeSeconds = unknownGauge(meters, "jobs.overdue.oldest.age");
        oldestExpiredTombstoneAgeSeconds =
                unknownGauge(meters, "tombstones.expired.oldest.age");
        health = gauge(meters, "health");
    }

    private TestSuiteStabilityJobRetentionTelemetry() {
        enabled = false;
        attempts = Map.of();
        duration = null;
        totalJobsTombstoned = new AtomicLong();
        totalTombstonesPurged = new AtomicLong();
        detailedJobRecords = new AtomicLong();
        tombstoneRecords = new AtomicLong();
        overdueJobRecords = new AtomicLong();
        expiredTombstoneRecords = new AtomicLong();
        lastSuccessEpochSeconds = new AtomicLong();
        retentionSuccessAgeSeconds = new AtomicLong(-1);
        oldestOverdueJobAgeSeconds = new AtomicLong(-1);
        oldestExpiredTombstoneAgeSeconds = new AtomicLong(-1);
        health = new AtomicLong();
    }

    /** @return disabled adapter for focused scheduler tests */
    public static TestSuiteStabilityJobRetentionTelemetry noop() {
        return new TestSuiteStabilityJobRetentionTelemetry();
    }

    /** Records one completed or lease-busy attempt and its wall-clock duration. */
    public void record(TestSuiteStabilityJobRetentionAttempt attempt, Duration elapsed) {
        TestSuiteStabilityJobRetentionAttempt safe = Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(elapsed, "elapsed");
        if (!enabled) {
            return;
        }
        Result result = safe.status() == TestSuiteStabilityJobRetentionAttempt.Status.COMPLETED
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

    /** Replaces aggregate gauges from one integrity-verified database snapshot. */
    public void refresh(TestSuiteStabilityJobRetentionSnapshot snapshot) {
        TestSuiteStabilityJobRetentionSnapshot safe = Objects.requireNonNull(snapshot, "snapshot");
        totalJobsTombstoned.set(safe.totalJobsTombstoned());
        totalTombstonesPurged.set(safe.totalTombstonesPurged());
        detailedJobRecords.set(safe.detailedJobRecords());
        tombstoneRecords.set(safe.tombstoneRecords());
        overdueJobRecords.set(safe.overdueJobRecords());
        expiredTombstoneRecords.set(safe.expiredTombstoneRecords());
        lastSuccessEpochSeconds.set(
                safe.lastSuccessAt() == null ? 0L : safe.lastSuccessAt().getEpochSecond());
    }

    /** Publishes one assessed SLO view without adding identity-derived labels. */
    public void observeSlo(
            TestSuiteStabilityJobRetentionSnapshot snapshot,
            TestSuiteStabilityJobRetentionSloMonitor.State state,
            Duration retentionSuccessAge,
            Duration oldestOverdueJobAge,
            Duration oldestExpiredTombstoneAge) {
        Objects.requireNonNull(state, "state");
        refresh(snapshot);
        if (!enabled) {
            return;
        }
        retentionSuccessAgeSeconds.set(secondsOrUnknown(retentionSuccessAge));
        oldestOverdueJobAgeSeconds.set(secondsOrUnknown(oldestOverdueJobAge));
        oldestExpiredTombstoneAgeSeconds.set(
                secondsOrUnknown(oldestExpiredTombstoneAge));
        health.set(switch (state) {
            case HEALTHY -> 1;
            case INITIALIZING -> 0;
            case SLO_VIOLATED -> -1;
            case STORE_UNAVAILABLE -> -2;
        });
    }

    /** Marks SLO gauges unavailable without retaining exception or storage details. */
    public void observeStoreUnavailable() {
        if (enabled) {
            health.set(-2);
            retentionSuccessAgeSeconds.set(-1);
            oldestOverdueJobAgeSeconds.set(-1);
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

    private static AtomicLong unknownGauge(MeterRegistry registry, String suffix) {
        AtomicLong value = gauge(registry, suffix);
        value.set(-1);
        return value;
    }
}
