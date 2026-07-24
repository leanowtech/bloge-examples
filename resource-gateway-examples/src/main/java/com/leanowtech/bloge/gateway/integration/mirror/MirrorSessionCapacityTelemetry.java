package com.leanowtech.bloge.gateway.integration.mirror;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixed-cardinality admission counters and payload-free capacity gauges for mirror sessions.
 *
 * <p>Every tag value comes from a closed enum. Scope, tenant, session, request, actor, correlation,
 * exception, and payload values can never enter the metric identity.</p>
 */
public final class MirrorSessionCapacityTelemetry {
    private static final String PREFIX = "resource.gateway.mirror.session.";
    private final boolean enabled;
    private final Map<Boundary, Map<Decision, Counter>> decisions;
    private final Map<ExpiryOutcome, Counter> expirySweeps;
    private final Map<AttemptReconciliationOutcome, Counter>
            attemptReconciliationSweeps;
    private final AtomicLong inflightCommands;
    private final AtomicLong activeSessions;
    private final AtomicLong retainedPayloadBytes;
    private final AtomicLong expiredRetainedPayloadBytes;
    private final AtomicLong maximumActiveSessions;
    private final AtomicLong maximumRetainedPayloadBytes;
    private final AtomicLong lastExpiredSessions;
    private final AtomicLong lastReconciledWriteAttempts;

    /** Closed capacity-decision authorities. */
    public enum Boundary {
        REPLICA,
        DATA_PLANE
    }

    /** Closed terminal admission outcomes. */
    public enum Decision {
        ADMITTED,
        REJECTED
    }

    /** Closed terminal outcomes for the bounded expiry worker. */
    public enum ExpiryOutcome {
        SUCCEEDED,
        FAILED,
        SKIPPED
    }

    /** Closed terminal outcomes for the durable write-attempt reconciler. */
    public enum AttemptReconciliationOutcome {
        SUCCEEDED,
        FAILED,
        SKIPPED
    }

    /**
     * Registers the complete bounded metric series before serving traffic.
     *
     * @param registry deployment-selected Micrometer registry
     */
    public MirrorSessionCapacityTelemetry(MeterRegistry registry) {
        MeterRegistry meters = Objects.requireNonNull(registry, "registry");
        enabled = true;
        EnumMap<Boundary, Map<Decision, Counter>> registered =
                new EnumMap<>(Boundary.class);
        for (Boundary boundary : Boundary.values()) {
            EnumMap<Decision, Counter> byDecision =
                    new EnumMap<>(Decision.class);
            for (Decision decision : Decision.values()) {
                byDecision.put(decision, Counter.builder(
                                PREFIX + "admission.decisions")
                        .tag("boundary", metric(boundary))
                        .tag("decision", metric(decision))
                        .register(meters));
            }
            registered.put(boundary, Map.copyOf(byDecision));
        }
        decisions = Map.copyOf(registered);
        EnumMap<ExpiryOutcome, Counter> sweepCounters =
                new EnumMap<>(ExpiryOutcome.class);
        for (ExpiryOutcome outcome : ExpiryOutcome.values()) {
            sweepCounters.put(outcome, Counter.builder(
                            PREFIX + "expiry.sweeps")
                    .tag("outcome", metric(outcome))
                    .register(meters));
        }
        expirySweeps = Map.copyOf(sweepCounters);
        EnumMap<AttemptReconciliationOutcome, Counter>
                reconciliationCounters =
                new EnumMap<>(
                        AttemptReconciliationOutcome.class);
        for (AttemptReconciliationOutcome outcome
                : AttemptReconciliationOutcome.values()) {
            reconciliationCounters.put(
                    outcome, Counter.builder(
                                    PREFIX
                                            + "write.attempt.reconciliation.sweeps")
                            .tag("outcome", metric(outcome))
                            .register(meters));
        }
        attemptReconciliationSweeps =
                Map.copyOf(reconciliationCounters);
        inflightCommands = gauge(meters, "commands.inflight");
        activeSessions = gauge(meters, "capacity.active.sessions");
        retainedPayloadBytes = gauge(
                meters, "capacity.retained.payload.bytes");
        expiredRetainedPayloadBytes = gauge(
                meters, "capacity.expired.retained.payload.bytes");
        maximumActiveSessions = gauge(
                meters, "capacity.maximum.sessions");
        maximumRetainedPayloadBytes = gauge(
                meters, "capacity.maximum.retained.payload.bytes");
        lastExpiredSessions = gauge(
                meters, "expiry.last.expired.sessions");
        lastReconciledWriteAttempts = gauge(
                meters,
                "write.attempt.reconciliation.last.terminalized");
    }

    private MirrorSessionCapacityTelemetry() {
        enabled = false;
        decisions = Map.of();
        expirySweeps = Map.of();
        attemptReconciliationSweeps = Map.of();
        inflightCommands = new AtomicLong();
        activeSessions = new AtomicLong();
        retainedPayloadBytes = new AtomicLong();
        expiredRetainedPayloadBytes = new AtomicLong();
        maximumActiveSessions = new AtomicLong();
        maximumRetainedPayloadBytes = new AtomicLong();
        lastExpiredSessions = new AtomicLong();
        lastReconciledWriteAttempts = new AtomicLong();
    }

    /**
     * Returns an inert adapter for focused tests or non-Micrometer embedding.
     *
     * @return telemetry adapter retaining no measurements
     */
    public static MirrorSessionCapacityTelemetry noop() {
        return new MirrorSessionCapacityTelemetry();
    }

    /**
     * Records one terminal capacity decision.
     *
     * @param boundary closed admission authority
     * @param decision terminal outcome
     */
    public void record(Boundary boundary, Decision decision) {
        if (enabled) {
            decisions.get(Objects.requireNonNull(boundary, "boundary"))
                    .get(Objects.requireNonNull(decision, "decision"))
                    .increment();
        }
    }

    /** Increments the payload-free local in-flight command gauge. */
    public void commandStarted() {
        inflightCommands.incrementAndGet();
    }

    /** Decrements the payload-free local in-flight command gauge. */
    public void commandFinished() {
        inflightCommands.updateAndGet(current -> Math.max(0, current - 1));
    }

    /**
     * Publishes the latest database-authoritative global capacity observation.
     *
     * @param snapshot payload-free global capacity snapshot
     */
    public void observe(MirrorSessionStateStore.CapacitySnapshot snapshot) {
        MirrorSessionStateStore.CapacitySnapshot value =
                Objects.requireNonNull(snapshot, "snapshot");
        activeSessions.set(value.activeSessions());
        retainedPayloadBytes.set(value.retainedPayloadBytes());
        expiredRetainedPayloadBytes.set(
                value.expiredRetainedPayloadBytes());
        maximumActiveSessions.set(value.maximumActiveSessions());
        maximumRetainedPayloadBytes.set(
                value.maximumRetainedPayloadBytes());
    }

    /**
     * Records one successful bounded expiry sweep.
     *
     * @param expiredSessions non-negative number of erased sessions
     */
    public void expirySweepCompleted(int expiredSessions) {
        if (expiredSessions < 0) {
            throw new IllegalArgumentException(
                    "expired session count must not be negative");
        }
        lastExpiredSessions.set(expiredSessions);
        if (enabled) {
            expirySweeps.get(ExpiryOutcome.SUCCEEDED).increment();
        }
    }

    /** Records a payload-free expiry sweep failure. */
    public void expirySweepFailed() {
        if (enabled) {
            expirySweeps.get(ExpiryOutcome.FAILED).increment();
        }
    }

    /** Records a skipped overlapping expiry sweep. */
    public void expirySweepSkipped() {
        if (enabled) {
            expirySweeps.get(ExpiryOutcome.SKIPPED).increment();
        }
    }

    /**
     * Records one successful bounded write-attempt reconciliation sweep.
     *
     * @param terminalizedAttempts number of expired intents resolved
     */
    public void writeAttemptReconciliationCompleted(
            int terminalizedAttempts) {
        if (terminalizedAttempts < 0) {
            throw new IllegalArgumentException(
                    "terminalized write-attempt count must not be negative");
        }
        lastReconciledWriteAttempts.set(
                terminalizedAttempts);
        if (enabled) {
            attemptReconciliationSweeps.get(
                    AttemptReconciliationOutcome.SUCCEEDED)
                    .increment();
        }
    }

    /** Records one payload-free write-attempt reconciliation failure. */
    public void writeAttemptReconciliationFailed() {
        if (enabled) {
            attemptReconciliationSweeps.get(
                    AttemptReconciliationOutcome.FAILED)
                    .increment();
        }
    }

    /** Records one overlapping local reconciliation tick that was skipped. */
    public void writeAttemptReconciliationSkipped() {
        if (enabled) {
            attemptReconciliationSweeps.get(
                    AttemptReconciliationOutcome.SKIPPED)
                    .increment();
        }
    }

    private static AtomicLong gauge(
            MeterRegistry registry, String suffix) {
        AtomicLong value = new AtomicLong();
        Gauge.builder(PREFIX + suffix, value, AtomicLong::doubleValue)
                .register(registry);
        return value;
    }

    private static String metric(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
