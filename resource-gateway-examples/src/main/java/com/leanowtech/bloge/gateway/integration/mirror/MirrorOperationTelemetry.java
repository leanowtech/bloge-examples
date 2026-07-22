package com.leanowtech.bloge.gateway.integration.mirror;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Fixed-cardinality counters and latency timers for protected Mirror operations.
 *
 * <p>All series are registered from closed enums. Tenant, scope, correlation, actor, request,
 * plan, run, exception, and diagnostic values can never become metric tags.</p>
 */
public final class MirrorOperationTelemetry {
    private static final String OPERATIONS = "resource.gateway.mirror.operations";
    private static final String FAILURES = "resource.gateway.mirror.failures";
    private static final String DURATION = "resource.gateway.mirror.duration";

    private final boolean enabled;
    private final Map<MirrorOperationAuditEvent.Operation,
            Map<MirrorOperationAuditEvent.Outcome, Counter>> outcomes;
    private final Map<MirrorOperationAuditEvent.Operation,
            Map<MirrorOperationAuditEvent.Outcome, Timer>> durations;
    private final Map<MirrorOperationAuditEvent.Operation,
            Map<MirrorOperationAuditEvent.Reason, Counter>> failures;

    /**
     * Registers every closed operation/outcome/reason series before serving traffic.
     *
     * @param registry deployment meter registry
     */
    public MirrorOperationTelemetry(MeterRegistry registry) {
        MeterRegistry meters = Objects.requireNonNull(registry, "registry");
        enabled = true;
        EnumMap<MirrorOperationAuditEvent.Operation,
                Map<MirrorOperationAuditEvent.Outcome, Counter>> outcomeCounters =
                new EnumMap<>(MirrorOperationAuditEvent.Operation.class);
        EnumMap<MirrorOperationAuditEvent.Operation,
                Map<MirrorOperationAuditEvent.Outcome, Timer>> durationTimers =
                new EnumMap<>(MirrorOperationAuditEvent.Operation.class);
        EnumMap<MirrorOperationAuditEvent.Operation,
                Map<MirrorOperationAuditEvent.Reason, Counter>> failureCounters =
                new EnumMap<>(MirrorOperationAuditEvent.Operation.class);
        for (MirrorOperationAuditEvent.Operation operation
                : MirrorOperationAuditEvent.Operation.values()) {
            EnumMap<MirrorOperationAuditEvent.Outcome, Counter> byOutcome =
                    new EnumMap<>(MirrorOperationAuditEvent.Outcome.class);
            EnumMap<MirrorOperationAuditEvent.Outcome, Timer> timeByOutcome =
                    new EnumMap<>(MirrorOperationAuditEvent.Outcome.class);
            for (MirrorOperationAuditEvent.Outcome outcome
                    : MirrorOperationAuditEvent.Outcome.values()) {
                byOutcome.put(outcome, Counter.builder(OPERATIONS)
                        .tag("operation", metric(operation))
                        .tag("outcome", metric(outcome))
                        .register(meters));
                timeByOutcome.put(outcome, Timer.builder(DURATION)
                        .tag("operation", metric(operation))
                        .tag("outcome", metric(outcome))
                        .register(meters));
            }
            EnumMap<MirrorOperationAuditEvent.Reason, Counter> byReason =
                    new EnumMap<>(MirrorOperationAuditEvent.Reason.class);
            for (MirrorOperationAuditEvent.Reason reason
                    : MirrorOperationAuditEvent.Reason.values()) {
                if (reason != MirrorOperationAuditEvent.Reason.NONE) {
                    byReason.put(reason, Counter.builder(FAILURES)
                            .tag("operation", metric(operation))
                            .tag("reason", metric(reason))
                            .register(meters));
                }
            }
            outcomeCounters.put(operation, Map.copyOf(byOutcome));
            durationTimers.put(operation, Map.copyOf(timeByOutcome));
            failureCounters.put(operation, Map.copyOf(byReason));
        }
        outcomes = Map.copyOf(outcomeCounters);
        durations = Map.copyOf(durationTimers);
        failures = Map.copyOf(failureCounters);
    }

    private MirrorOperationTelemetry() {
        enabled = false;
        outcomes = Map.of();
        durations = Map.of();
        failures = Map.of();
    }

    /**
     * Returns an inert adapter for focused tests that do not exercise observability.
     *
     * @return telemetry adapter that retains and publishes no measurements
     */
    public static MirrorOperationTelemetry noop() {
        return new MirrorOperationTelemetry();
    }

    /**
     * Records one terminal operation without retaining any high-cardinality value.
     *
     * @param operation protected operation
     * @param outcome terminal operation outcome
     * @param reason closed failure class, or {@link MirrorOperationAuditEvent.Reason#NONE}
     * @param durationNanos non-negative monotonic duration in nanoseconds
     */
    public void record(
            MirrorOperationAuditEvent.Operation operation,
            MirrorOperationAuditEvent.Outcome outcome,
            MirrorOperationAuditEvent.Reason reason,
            long durationNanos) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(reason, "reason");
        if (!enabled) {
            return;
        }
        try {
            outcomes.get(operation).get(outcome).increment();
            durations.get(operation).get(outcome)
                    .record(Duration.ofNanos(Math.max(0, durationNanos)));
            if (reason != MirrorOperationAuditEvent.Reason.NONE) {
                failures.get(operation).get(reason).increment();
            }
        } catch (RuntimeException ignored) {
            // Metrics are advisory; the durable audit remains the fail-closed authority.
        }
    }

    private static String metric(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
