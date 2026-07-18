package com.leanowtech.bloge.gateway.testing.api;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Fixed-cardinality, payload-free telemetry for the suite-stability job queue and worker. */
public final class TestSuiteStabilityJobTelemetry {

    private static final String PREFIX = "resource.gateway.test.stability.jobs.";

    private final boolean enabled;
    private final Map<Environment, Map<TestSuiteStabilityJobRecord.Status, AtomicLong>> records;
    private final Map<Environment, AtomicLong> oldestQueuedAgeSeconds;
    private final Map<Environment, AtomicLong> expiredLiveLeases;
    private final Map<Environment, AtomicLong> distinctQueuedTenants;
    private final Map<Environment, AtomicLong> health;
    private final Map<Environment, Map<TestSuiteStabilityJobWorkResult.Outcome, Counter>> polls;
    private final Map<Environment, Counter> unexpectedPolls;
    private final AtomicLong workerConfigured;
    private final AtomicLong workerActivePolls;
    private final AtomicLong workerClosed;

    /**
     * Registers every environment, status, and worker-outcome series before any work executes.
     *
     * @param registry deployment-selected Micrometer registry
     */
    public TestSuiteStabilityJobTelemetry(MeterRegistry registry) {
        MeterRegistry meters = Objects.requireNonNull(registry, "registry");
        enabled = true;
        EnumMap<Environment, Map<TestSuiteStabilityJobRecord.Status, AtomicLong>> byEnvironment =
                new EnumMap<>(Environment.class);
        EnumMap<Environment, AtomicLong> ages = new EnumMap<>(Environment.class);
        EnumMap<Environment, AtomicLong> expired = new EnumMap<>(Environment.class);
        EnumMap<Environment, AtomicLong> tenants = new EnumMap<>(Environment.class);
        EnumMap<Environment, AtomicLong> states = new EnumMap<>(Environment.class);
        EnumMap<Environment, Map<TestSuiteStabilityJobWorkResult.Outcome, Counter>> outcomes =
                new EnumMap<>(Environment.class);
        EnumMap<Environment, Counter> unexpected = new EnumMap<>(Environment.class);
        for (Environment environment : Environment.values()) {
            String value = environment.metricValue();
            EnumMap<TestSuiteStabilityJobRecord.Status, AtomicLong> statusValues =
                    new EnumMap<>(TestSuiteStabilityJobRecord.Status.class);
            for (TestSuiteStabilityJobRecord.Status status
                    : TestSuiteStabilityJobRecord.Status.values()) {
                AtomicLong gauge = new AtomicLong();
                Gauge.builder(PREFIX + "queue.records", gauge, AtomicLong::doubleValue)
                        .tag("environment", value)
                        .tag("status", status.name().toLowerCase(Locale.ROOT))
                        .register(meters);
                statusValues.put(status, gauge);
            }
            byEnvironment.put(environment, Map.copyOf(statusValues));
            ages.put(environment, gauge(meters, "queue.oldest.age", environment, -1));
            expired.put(environment, gauge(meters, "queue.expired.leases", environment, 0));
            tenants.put(environment, gauge(meters, "queue.queued.tenants", environment, 0));
            states.put(environment, gauge(meters, "queue.health", environment, 0));
            EnumMap<TestSuiteStabilityJobWorkResult.Outcome, Counter> resultCounters =
                    new EnumMap<>(TestSuiteStabilityJobWorkResult.Outcome.class);
            for (TestSuiteStabilityJobWorkResult.Outcome outcome
                    : TestSuiteStabilityJobWorkResult.Outcome.values()) {
                resultCounters.put(outcome, Counter.builder(PREFIX + "worker.polls")
                        .tag("environment", value)
                        .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                        .register(meters));
            }
            outcomes.put(environment, Map.copyOf(resultCounters));
            unexpected.put(environment, Counter.builder(PREFIX + "worker.unexpected")
                    .tag("environment", value)
                    .register(meters));
        }
        records = Map.copyOf(byEnvironment);
        oldestQueuedAgeSeconds = Map.copyOf(ages);
        expiredLiveLeases = Map.copyOf(expired);
        distinctQueuedTenants = Map.copyOf(tenants);
        health = Map.copyOf(states);
        polls = Map.copyOf(outcomes);
        unexpectedPolls = Map.copyOf(unexpected);
        workerConfigured = gauge(meters, "worker.configured", 0);
        workerActivePolls = gauge(meters, "worker.active", 0);
        workerClosed = gauge(meters, "worker.closed", 0);
    }

    private TestSuiteStabilityJobTelemetry() {
        enabled = false;
        records = Map.of();
        oldestQueuedAgeSeconds = Map.of();
        expiredLiveLeases = Map.of();
        distinctQueuedTenants = Map.of();
        health = Map.of();
        polls = Map.of();
        unexpectedPolls = Map.of();
        workerConfigured = new AtomicLong();
        workerActivePolls = new AtomicLong();
        workerClosed = new AtomicLong();
    }

    /** @return disabled adapter for focused worker and monitor tests */
    public static TestSuiteStabilityJobTelemetry noop() {
        return new TestSuiteStabilityJobTelemetry();
    }

    /** Replaces one environment's aggregate gauges from a database-clock observation. */
    public void observe(
            String environmentId,
            TestSuiteStabilityQueueSnapshot snapshot,
            TestSuiteStabilityJobSloMonitor.State state,
            Duration oldestQueuedAge) {
        Environment environment = Environment.parse(environmentId);
        TestSuiteStabilityQueueSnapshot safe = Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(state, "state");
        if (!enabled) {
            return;
        }
        safe.totals().forEach((status, total) ->
                records.get(environment).get(status).set(total));
        oldestQueuedAgeSeconds.get(environment).set(
                oldestQueuedAge == null ? -1 : Math.max(0L, oldestQueuedAge.toSeconds()));
        expiredLiveLeases.get(environment).set(safe.expiredLiveLeases());
        distinctQueuedTenants.get(environment).set(safe.distinctQueuedTenants());
        health.get(environment).set(switch (state) {
            case HEALTHY -> 1;
            case SLO_VIOLATED -> -1;
            case STORE_UNAVAILABLE -> -2;
        });
    }

    /** Marks one environment's queue observation unknown without retaining exception text. */
    public void observeStoreUnavailable(String environmentId) {
        Environment environment = Environment.parse(environmentId);
        if (!enabled) {
            return;
        }
        records.get(environment).values().forEach(value -> value.set(-1));
        oldestQueuedAgeSeconds.get(environment).set(-1);
        expiredLiveLeases.get(environment).set(-1);
        distinctQueuedTenants.get(environment).set(-1);
        health.get(environment).set(-2);
    }

    /** Records one payload-free bounded worker result. */
    public void recordPoll(String environmentId, TestSuiteStabilityJobWorkResult result) {
        if (enabled) {
            Environment environment = Environment.parse(environmentId);
            polls.get(environment).get(Objects.requireNonNull(result, "result").outcome())
                    .increment();
        }
    }

    /** Records one worker exception or invalid null result without its message. */
    public void recordUnexpectedPoll(String environmentId) {
        if (enabled) {
            unexpectedPolls.get(Environment.parse(environmentId)).increment();
        }
    }

    /** Marks the process as configured to run stability worker lanes. */
    public void workerStarted() {
        workerConfigured.set(1);
        workerClosed.set(0);
    }

    /** Replaces the process-local active poll gauge. */
    public void activePolls(int active) {
        if (active < 0) {
            throw new IllegalArgumentException("active stability polls must be non-negative");
        }
        workerActivePolls.set(active);
    }

    /** Marks local scheduling closed while retaining any uninterruptible active count. */
    public void workerStopped(int active) {
        activePolls(active);
        workerClosed.set(1);
    }

    private static AtomicLong gauge(
            MeterRegistry registry, String suffix, Environment environment, long initialValue) {
        AtomicLong value = new AtomicLong(initialValue);
        Gauge.builder(PREFIX + suffix, value, AtomicLong::doubleValue)
                .tag("environment", environment.metricValue())
                .register(registry);
        return value;
    }

    private static AtomicLong gauge(MeterRegistry registry, String suffix, long initialValue) {
        AtomicLong value = new AtomicLong(initialValue);
        Gauge.builder(PREFIX + suffix, value, AtomicLong::doubleValue).register(registry);
        return value;
    }

    private enum Environment {
        TEST,
        STAGING;

        private static Environment parse(String value) {
            try {
                return valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException(
                        "Stability telemetry environment must be test or staging", invalid);
            }
        }

        private String metricValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
