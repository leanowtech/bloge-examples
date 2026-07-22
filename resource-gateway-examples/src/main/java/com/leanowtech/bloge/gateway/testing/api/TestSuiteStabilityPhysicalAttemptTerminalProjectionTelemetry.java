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
 * Fixed-cardinality, payload-free telemetry for physical-attempt terminal projection.
 *
 * <p>Every possible worker outcome and local disposition is registered at construction. No
 * tenant, environment, attempt, lease, owner, provider, exception, or failure message can become
 * a metric label.</p>
 */
public final class TestSuiteStabilityPhysicalAttemptTerminalProjectionTelemetry {

    private static final String PREFIX =
            "resource.gateway.test.stability.physical.attempt.terminal.projection.";

    private final boolean enabled;
    private final Map<TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Outcome, Counter>
            outcomes;
    private final Map<TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.LocalDisposition,
            Counter> localDispositions;
    private final Counter unexpectedPolls;
    private final AtomicLong configured;
    private final AtomicLong activePolls;
    private final AtomicLong closed;

    /**
     * Registers the closed outcome dimensions and lifecycle gauges.
     *
     * @param registry deployment-selected Micrometer registry
     */
    public TestSuiteStabilityPhysicalAttemptTerminalProjectionTelemetry(
            MeterRegistry registry) {
        MeterRegistry meters = Objects.requireNonNull(registry, "registry");
        enabled = true;
        EnumMap<TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Outcome, Counter>
                resultCounters = new EnumMap<>(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Outcome.class);
        for (TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Outcome outcome
                : TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Outcome.values()) {
            resultCounters.put(outcome, Counter.builder(PREFIX + "worker.polls")
                    .tag("outcome", metricValue(outcome))
                    .register(meters));
        }
        outcomes = Map.copyOf(resultCounters);
        EnumMap<TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.LocalDisposition,
                Counter> dispositionCounters = new EnumMap<>(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.LocalDisposition.class);
        for (TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.LocalDisposition disposition
                : TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.LocalDisposition
                .values()) {
            dispositionCounters.put(disposition,
                    Counter.builder(PREFIX + "worker.local.dispositions")
                            .tag("disposition", metricValue(disposition))
                            .register(meters));
        }
        localDispositions = Map.copyOf(dispositionCounters);
        unexpectedPolls = Counter.builder(PREFIX + "worker.unexpected").register(meters);
        configured = gauge(meters, "worker.configured");
        activePolls = gauge(meters, "worker.active");
        closed = gauge(meters, "worker.closed");
    }

    private TestSuiteStabilityPhysicalAttemptTerminalProjectionTelemetry() {
        enabled = false;
        outcomes = Map.of();
        localDispositions = Map.of();
        unexpectedPolls = null;
        configured = new AtomicLong();
        activePolls = new AtomicLong();
        closed = new AtomicLong();
    }

    /**
     * Returns a disabled adapter for focused unit tests and embedders without Micrometer.
     *
     * @return inert telemetry adapter
     */
    public static TestSuiteStabilityPhysicalAttemptTerminalProjectionTelemetry noop() {
        return new TestSuiteStabilityPhysicalAttemptTerminalProjectionTelemetry();
    }

    /**
     * Records one bounded, payload-free worker execution.
     *
     * @param execution closed worker outcome and local disposition
     */
    public void recordPoll(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Execution execution) {
        TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Execution result =
                Objects.requireNonNull(execution, "execution");
        if (enabled) {
            outcomes.get(result.outcome()).increment();
            localDispositions.get(result.localDisposition()).increment();
        }
    }

    /** Records a thrown exception or invalid null result without diagnostic text. */
    public void recordUnexpectedPoll() {
        if (enabled) {
            unexpectedPolls.increment();
        }
    }

    /** Marks the process as configured to run terminal-projection lanes. */
    public void workerStarted() {
        configured.set(1L);
        closed.set(0L);
    }

    /**
     * Replaces the process-local active poll gauge.
     *
     * @param active current synchronous worker calls
     */
    public void activePolls(int active) {
        if (active < 0) {
            throw new IllegalArgumentException(
                    "Active physical-attempt terminal projection polls must be non-negative");
        }
        activePolls.set(active);
    }

    /**
     * Marks local scheduling closed while retaining any uninterruptible active count.
     *
     * @param active current synchronous worker calls after bounded drain
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
