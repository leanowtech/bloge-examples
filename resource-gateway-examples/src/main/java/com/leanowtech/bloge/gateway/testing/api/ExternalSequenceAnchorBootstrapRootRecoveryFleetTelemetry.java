package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.Status;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetSloMonitor.Assessment;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetSloMonitor.State;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetSloMonitor.Violation;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixed-cardinality Micrometer projection of recovery-fleet progress SLO truth.
 *
 * <p>Every tag value comes from a closed enum or vocabulary. Fleet, worker, deployment, scope,
 * root-set, URI, fingerprint, exception, credential, and business-payload values are excluded.
 * The gauges represent the latest local assessment and are alert-ready, but external collection,
 * routing, deduplication, and paging remain deployment responsibilities.</p>
 */
public final class ExternalSequenceAnchorBootstrapRootRecoveryFleetTelemetry {

    static final String PREFIX = "resource.gateway.test.bootstrap.root.recovery.fleet.";

    private final boolean enabled;
    private final AtomicLong health = new AtomicLong(-3L);
    private final Map<Status, AtomicLong> statuses;
    private final Map<Violation, AtomicLong> violations;
    private final AtomicLong inventoryGeneration = unknown();
    private final AtomicLong laneCount = unknown();
    private final AtomicLong pollsTotal = unknown();
    private final AtomicLong pollsCompleted = unknown();
    private final AtomicLong pollsFailed = unknown();
    private final AtomicLong cyclesTotal = unknown();
    private final AtomicLong cyclesFailed = unknown();
    private final AtomicLong lanesAttempted = unknown();
    private final AtomicLong lanesFailed = unknown();
    private final AtomicLong pollFailureBasisPoints = unknown();
    private final AtomicLong cycleFailureBasisPoints = unknown();
    private final AtomicLong laneFailureBasisPoints = unknown();
    private final AtomicLong lastSuccessAgeMillis = unknown();

    /**
     * Registers the complete bounded metric vocabulary.
     *
     * @param registry deployment-selected Micrometer registry
     */
    public ExternalSequenceAnchorBootstrapRootRecoveryFleetTelemetry(MeterRegistry registry) {
        MeterRegistry meters = Objects.requireNonNull(registry, "registry");
        enabled = true;
        statuses = enumGauges(meters, PREFIX + "status", "status", Status.values());
        violations = enumGauges(
                meters, PREFIX + "violation", "code", Violation.values());
        gauge(meters, PREFIX + "health", health,
                "Latest local SLO health: 1 healthy, 0 initializing, -1 violated, "
                        + "-2 closed, -3 unavailable");
        gauge(meters, PREFIX + "inventory.generation", inventoryGeneration,
                "Current externally attested inventory generation, or -1 when unavailable");
        gauge(meters, PREFIX + "inventory.lanes", laneCount,
                "Current externally attested lane count, or -1 when unavailable");
        taggedGauge(meters, PREFIX + "polls", "outcome", "total", pollsTotal);
        taggedGauge(meters, PREFIX + "polls", "outcome", "completed", pollsCompleted);
        taggedGauge(meters, PREFIX + "polls", "outcome", "failed", pollsFailed);
        taggedGauge(meters, PREFIX + "cycles", "outcome", "total", cyclesTotal);
        taggedGauge(meters, PREFIX + "cycles", "outcome", "failed", cyclesFailed);
        taggedGauge(meters, PREFIX + "lanes", "outcome", "attempted", lanesAttempted);
        taggedGauge(meters, PREFIX + "lanes", "outcome", "failed", lanesFailed);
        taggedGauge(meters, PREFIX + "failure.ratio.basis.points", "scope", "poll",
                pollFailureBasisPoints);
        taggedGauge(meters, PREFIX + "failure.ratio.basis.points", "scope", "cycle",
                cycleFailureBasisPoints);
        taggedGauge(meters, PREFIX + "failure.ratio.basis.points", "scope", "lane",
                laneFailureBasisPoints);
        gauge(meters, PREFIX + "last.success.age.millis", lastSuccessAgeMillis,
                "Age of the latest successful local poll, or -1 when unknown");
    }

    private ExternalSequenceAnchorBootstrapRootRecoveryFleetTelemetry() {
        enabled = false;
        statuses = Map.of();
        violations = Map.of();
    }

    /**
     * Creates an allocation-light disabled adapter.
     *
     * @return telemetry adapter that accepts observations without registering meters
     */
    public static ExternalSequenceAnchorBootstrapRootRecoveryFleetTelemetry noop() {
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetTelemetry();
    }

    /**
     * Replaces every gauge from one immutable SLO assessment.
     *
     * @param assessment latest versioned local assessment
     */
    public void observe(Assessment assessment) {
        if (!enabled) {
            return;
        }
        Assessment observed = Objects.requireNonNull(assessment, "assessment");
        health.set(healthValue(observed.state()));
        statuses.forEach((status, gauge) ->
                gauge.set(status == observed.runtimeStatus() ? 1L : 0L));
        violations.forEach((violation, gauge) ->
                gauge.set(observed.violations().contains(violation) ? 1L : 0L));
        inventoryGeneration.set(observed.inventoryGeneration());
        laneCount.set(observed.laneCount());
        pollsTotal.set(observed.pollCount());
        pollsCompleted.set(observed.completedPollCount());
        pollsFailed.set(observed.pollFailureCount());
        cyclesTotal.set(observed.cycleCount());
        cyclesFailed.set(observed.cycleFailureCount());
        lanesAttempted.set(observed.laneAttemptCount());
        lanesFailed.set(observed.laneFailureCount());
        pollFailureBasisPoints.set(observed.pollFailureBasisPoints());
        cycleFailureBasisPoints.set(observed.cycleFailureBasisPoints());
        laneFailureBasisPoints.set(observed.laneFailureBasisPoints());
        lastSuccessAgeMillis.set(observed.lastPollSuccessAgeMillis());
    }

    private static long healthValue(State state) {
        return switch (Objects.requireNonNull(state, "state")) {
            case HEALTHY -> 1L;
            case INITIALIZING -> 0L;
            case SLO_VIOLATED -> -1L;
            case CLOSED -> -2L;
            case OBSERVATION_UNAVAILABLE -> -3L;
        };
    }

    private static <E extends Enum<E>> Map<E, AtomicLong> enumGauges(
            MeterRegistry registry, String name, String tagName, E[] values) {
        Map<E, AtomicLong> gauges = new EnumMap<>(values[0].getDeclaringClass());
        for (E value : values) {
            AtomicLong gauge = new AtomicLong();
            gauges.put(value, gauge);
            taggedGauge(registry, name, tagName,
                    value.name().toLowerCase(Locale.ROOT), gauge);
        }
        return Map.copyOf(gauges);
    }

    private static void taggedGauge(
            MeterRegistry registry,
            String name,
            String tagName,
            String tagValue,
            AtomicLong value) {
        Gauge.builder(name, value, AtomicLong::doubleValue)
                .tag(tagName, tagValue).register(registry);
    }

    private static void gauge(
            MeterRegistry registry, String name, AtomicLong value, String description) {
        Gauge.builder(name, value, AtomicLong::doubleValue)
                .description(description).register(registry);
    }

    private static AtomicLong unknown() {
        return new AtomicLong(-1L);
    }
}
