package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableStateProjectionControlPlane;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded-cardinality Micrometer adapter for durable projection control loops.
 *
 * <p>Metric tags are closed enums. Finding identities, tenant values, claim tokens, exception
 * messages, and business payloads never become meter names, tags, or values.</p>
 */
public final class DurableStateProjectionTelemetry {
    private static final String PREFIX = "resource.gateway.test.projection.";

    private final boolean enabled;
    private final Map<Result, Counter> reconciliationAttempts;
    private final Map<Result, Counter> retentionAttempts;
    private final Timer reconciliationDuration;
    private final Timer retentionDuration;
    private final AtomicLong openFindings = new AtomicLong();
    private final AtomicLong liveClaimedFindings = new AtomicLong();
    private final AtomicLong expiredClaimFindings = new AtomicLong();
    private final AtomicLong resolvedFindings = new AtomicLong();
    private final AtomicLong activeRetentionBacklog = new AtomicLong();
    private final AtomicLong archiveRetentionBacklog = new AtomicLong();
    private final AtomicLong reconciliationSuccessAge = new AtomicLong(-1);
    private final AtomicLong retentionSuccessAge = new AtomicLong(-1);
    private final AtomicLong health = new AtomicLong();

    /**
     * Registers the fixed metric vocabulary with the application meter registry.
     *
     * @param registry deployment-selected Micrometer registry
     */
    public DurableStateProjectionTelemetry(MeterRegistry registry) {
        MeterRegistry meters = Objects.requireNonNull(registry, "registry");
        enabled = true;
        reconciliationAttempts = counters(
                meters, PREFIX + "reconciliation.attempts");
        retentionAttempts = counters(meters, PREFIX + "retention.attempts");
        reconciliationDuration = Timer.builder(PREFIX + "reconciliation.duration")
                .description("Duration of durable projection reconciliation attempts")
                .register(meters);
        retentionDuration = Timer.builder(PREFIX + "retention.duration")
                .description("Duration of durable projection retention attempts")
                .register(meters);
        gauge(meters, PREFIX + "findings", "state", "open", openFindings);
        gauge(meters, PREFIX + "findings", "state", "claimed_live", liveClaimedFindings);
        gauge(meters, PREFIX + "findings", "state", "claim_expired", expiredClaimFindings);
        gauge(meters, PREFIX + "findings", "state", "resolved", resolvedFindings);
        gauge(meters, PREFIX + "retention.backlog", "tier", "active",
                activeRetentionBacklog);
        gauge(meters, PREFIX + "retention.backlog", "tier", "archive",
                archiveRetentionBacklog);
        gauge(meters, PREFIX + "last_success.age", "loop", "reconciliation",
                reconciliationSuccessAge);
        gauge(meters, PREFIX + "last_success.age", "loop", "retention",
                retentionSuccessAge);
        Gauge.builder(PREFIX + "health", health, AtomicLong::doubleValue)
                .description("Projection control-plane health: 1 healthy, 0 initializing, "
                        + "-1 SLO violated, -2 store unavailable")
                .register(meters);
    }

    private DurableStateProjectionTelemetry() {
        enabled = false;
        reconciliationAttempts = Map.of();
        retentionAttempts = Map.of();
        reconciliationDuration = null;
        retentionDuration = null;
    }

    /**
     * Creates a disabled adapter.
     *
     * @return an allocation-light adapter for isolated unit tests and legacy constructors
     */
    public static DurableStateProjectionTelemetry noop() {
        return new DurableStateProjectionTelemetry();
    }

    /**
     * Records one completed or lease-busy reconciliation attempt.
     *
     * @param attempt aggregate control-plane result
     * @param duration local monotonic elapsed time
     */
    public void recordReconciliation(
            DatabaseDurableStateProjectionControlPlane.SweepAttempt attempt,
            Duration duration) {
        Objects.requireNonNull(attempt, "attempt");
        record(reconciliationAttempts,
                attempt.status() == DatabaseDurableStateProjectionControlPlane.SweepStatus.COMPLETED
                        ? Result.COMPLETED : Result.BUSY,
                reconciliationDuration, duration);
    }

    /**
     * Records a failed reconciliation attempt without retaining failure details.
     *
     * @param duration local monotonic elapsed time
     */
    public void recordReconciliationFailure(Duration duration) {
        record(reconciliationAttempts, Result.FAILED, reconciliationDuration, duration);
    }

    /**
     * Records one completed or lease-busy retention attempt.
     *
     * @param attempt aggregate control-plane result
     * @param duration local monotonic elapsed time
     */
    public void recordRetention(
            DatabaseDurableStateProjectionControlPlane.RetentionAttempt attempt,
            Duration duration) {
        Objects.requireNonNull(attempt, "attempt");
        record(retentionAttempts,
                attempt.status() == DatabaseDurableStateProjectionControlPlane.RetentionStatus.COMPLETED
                        ? Result.COMPLETED : Result.BUSY,
                retentionDuration, duration);
    }

    /**
     * Records a failed retention attempt without retaining failure details.
     *
     * @param duration local monotonic elapsed time
     */
    public void recordRetentionFailure(Duration duration) {
        record(retentionAttempts, Result.FAILED, retentionDuration, duration);
    }

    /**
     * Replaces aggregate gauges from one transactionally consistent database observation.
     *
     * @param snapshot payload-free database observation
     * @param state assessed aggregate health state
     * @param reconciliationAge age of the last reconciliation success, or {@code null}
     * @param retentionAge age of the last retention success, or {@code null}
     */
    public void observe(
            DatabaseDurableStateProjectionControlPlane.OperationalSnapshot snapshot,
            DurableStateProjectionSloMonitor.State state,
            Duration reconciliationAge,
            Duration retentionAge) {
        if (!enabled) {
            return;
        }
        Objects.requireNonNull(snapshot, "snapshot");
        openFindings.set(snapshot.openFindings());
        liveClaimedFindings.set(snapshot.liveClaimedFindings());
        expiredClaimFindings.set(snapshot.expiredClaimFindings());
        resolvedFindings.set(snapshot.resolvedFindings());
        activeRetentionBacklog.set(snapshot.overdueResolvedFindings());
        archiveRetentionBacklog.set(snapshot.overdueArchiveRecords());
        reconciliationSuccessAge.set(secondsOrUnknown(reconciliationAge));
        retentionSuccessAge.set(secondsOrUnknown(retentionAge));
        health.set(healthValue(state));
    }

    /** Marks the aggregate health gauge unavailable without exporting an exception message. */
    public void observeStoreUnavailable() {
        if (enabled) {
            health.set(-2);
        }
    }

    private void record(
            Map<Result, Counter> counters,
            Result result,
            Timer timer,
            Duration duration) {
        if (!enabled) {
            return;
        }
        counters.get(result).increment();
        timer.record(nonNegative(duration));
    }

    private static Map<Result, Counter> counters(MeterRegistry registry, String name) {
        Map<Result, Counter> counters = new EnumMap<>(Result.class);
        for (Result result : Result.values()) {
            counters.put(result, Counter.builder(name)
                    .tag("result", result.tag)
                    .register(registry));
        }
        return Map.copyOf(counters);
    }

    private static void gauge(
            MeterRegistry registry,
            String name,
            String tagName,
            String tagValue,
            AtomicLong value) {
        Gauge.builder(name, value, AtomicLong::doubleValue)
                .tag(tagName, tagValue)
                .register(registry);
    }

    private static Duration nonNegative(Duration duration) {
        Duration safe = Objects.requireNonNull(duration, "duration");
        return safe.isNegative() ? Duration.ZERO : safe;
    }

    private static long secondsOrUnknown(Duration duration) {
        return duration == null ? -1 : Math.max(0, duration.toSeconds());
    }

    private static long healthValue(DurableStateProjectionSloMonitor.State state) {
        return switch (Objects.requireNonNull(state, "state")) {
            case HEALTHY -> 1;
            case INITIALIZING -> 0;
            case SLO_VIOLATED -> -1;
            case STORE_UNAVAILABLE -> -2;
        };
    }

    private enum Result {
        COMPLETED("completed"),
        BUSY("busy"),
        FAILED("failed");

        private final String tag;

        Result(String tag) {
            this.tag = tag;
        }
    }
}
