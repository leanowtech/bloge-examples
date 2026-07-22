package com.leanowtech.bloge.gateway.testing.api;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixed-cardinality telemetry for autonomous physical-attempt observation reconciliation.
 *
 * <p>Only the closed reconciler stage enum is used as a label. Tenant, environment, attempt,
 * lease, worker, provider, deployment, exception, and diagnostic values are never labels.</p>
 */
public final class TestSuiteStabilityPhysicalAttemptObservationReconciliationTelemetry {

    private static final String PREFIX =
            "resource.gateway.test.stability.physical.attempt.observation.reconciliation.";

    private final boolean enabled;
    private final Map<TestSuiteStabilityPhysicalAttemptObservationReconciler.Stage, Counter> stages;
    private final Counter unexpectedPolls;
    private final AtomicLong configured;
    private final AtomicLong activePolls;
    private final AtomicLong closed;

    /**
     * Registers every closed stage and the three payload-free lifecycle gauges.
     *
     * @param registry deployment-selected Micrometer registry
     */
    public TestSuiteStabilityPhysicalAttemptObservationReconciliationTelemetry(
            MeterRegistry registry) {
        MeterRegistry meters = Objects.requireNonNull(registry, "registry");
        enabled = true;
        EnumMap<TestSuiteStabilityPhysicalAttemptObservationReconciler.Stage, Counter> counters =
                new EnumMap<>(TestSuiteStabilityPhysicalAttemptObservationReconciler.Stage.class);
        for (TestSuiteStabilityPhysicalAttemptObservationReconciler.Stage stage
                : TestSuiteStabilityPhysicalAttemptObservationReconciler.Stage.values()) {
            counters.put(stage, Counter.builder(PREFIX + "worker.polls")
                    .tag("stage", metricValue(stage))
                    .register(meters));
        }
        stages = Map.copyOf(counters);
        unexpectedPolls = Counter.builder(PREFIX + "worker.unexpected").register(meters);
        configured = gauge(meters, "worker.configured");
        activePolls = gauge(meters, "worker.active");
        closed = gauge(meters, "worker.closed");
    }

    private TestSuiteStabilityPhysicalAttemptObservationReconciliationTelemetry() {
        enabled = false;
        stages = Map.of();
        unexpectedPolls = null;
        configured = new AtomicLong();
        activePolls = new AtomicLong();
        closed = new AtomicLong();
    }

    /**
     * Returns an inert adapter for focused tests and embedders without Micrometer.
     *
     * @return disabled telemetry adapter
     */
    public static TestSuiteStabilityPhysicalAttemptObservationReconciliationTelemetry noop() {
        return new TestSuiteStabilityPhysicalAttemptObservationReconciliationTelemetry();
    }

    /**
     * Records one bounded reconciler attempt without retaining identity or payload.
     *
     * @param attempt closed reconciliation outcome
     */
    public void recordPoll(TestSuiteStabilityPhysicalAttemptObservationReconciler.Attempt attempt) {
        TestSuiteStabilityPhysicalAttemptObservationReconciler.Attempt result =
                Objects.requireNonNull(attempt, "attempt");
        if (enabled) {
            stages.get(result.stage()).increment();
        }
    }

    /** Records a thrown exception or invalid null result without diagnostic text. */
    public void recordUnexpectedPoll() {
        if (enabled) {
            unexpectedPolls.increment();
        }
    }

    /** Marks local reconciliation scheduling as configured and open. */
    public void workerStarted() {
        configured.set(1L);
        closed.set(0L);
    }

    /**
     * Replaces the process-local active poll gauge.
     *
     * @param active current synchronous reconciler calls
     */
    public void activePolls(int active) {
        if (active < 0) {
            throw new IllegalArgumentException(
                    "Active physical-attempt observation reconciliation polls must be "
                            + "non-negative");
        }
        activePolls.set(active);
    }

    /**
     * Marks scheduling closed while retaining any call that ignored interruption.
     *
     * @param active current synchronous reconciler calls after bounded drain
     */
    public void workerStopped(int active) {
        activePolls(active);
        closed.set(1L);
    }

    private static AtomicLong gauge(MeterRegistry registry, String suffix) {
        AtomicLong value = new AtomicLong();
        Gauge.builder(PREFIX + suffix, value, AtomicLong::doubleValue).register(registry);
        return value;
    }

    private static String metricValue(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
