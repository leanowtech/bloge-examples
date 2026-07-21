package com.leanowtech.bloge.gateway.testing.api;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Aggregate-only Actuator health for a process-local bootstrap-root recovery fleet.
 *
 * <p>The indicator reads immutable worker and scheduler snapshots only. It never reads the fleet
 * inventory, acquires a ceremony, resolves signer authorities, invokes a provider, or exposes a
 * scope, root set, worker, service, resolver, key, fingerprint, endpoint, payload, or exception.
 * An empty inventory and a clean no-work cycle are healthy.</p>
 *
 * <p>Readiness fails closed when lifecycle is closed, the local timer or active cycle is overdue,
 * the latest poll threw, the worker rejected an inventory generation, or the latest completed
 * cycle isolated at least one lane failure. A later clean cycle clears transient failure state.</p>
 */
public final class ExternalSequenceAnchorBootstrapRootRecoveryFleetHealth
        implements HealthIndicator {

    private final Supplier<ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.RuntimeSnapshot>
            workerSnapshot;
    private final Supplier<ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler.Snapshot>
            schedulerSnapshot;

    /**
     * Creates health over one scheduler and its caller-owned fleet worker.
     *
     * @param worker bounded recovery fleet worker
     * @param scheduler fixed-delay fleet scheduler
     */
    public ExternalSequenceAnchorBootstrapRootRecoveryFleetHealth(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker worker,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler scheduler) {
        this(Objects.requireNonNull(worker, "worker")::runtimeSnapshot,
                Objects.requireNonNull(scheduler, "scheduler")::snapshot);
    }

    ExternalSequenceAnchorBootstrapRootRecoveryFleetHealth(
            Supplier<ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.RuntimeSnapshot>
                    workerSnapshot,
            Supplier<ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler.Snapshot>
                    schedulerSnapshot) {
        this.workerSnapshot = Objects.requireNonNull(workerSnapshot, "workerSnapshot");
        this.schedulerSnapshot = Objects.requireNonNull(
                schedulerSnapshot, "schedulerSnapshot");
    }

    /**
     * Returns UP only while fleet lifecycle, scheduling, inventory, and latest lanes are usable.
     *
     * @return payload-free Actuator readiness projection
     */
    @Override
    public Health health() {
        try {
            var worker = workerSnapshot.get();
            var scheduler = schedulerSnapshot.get();
            RuntimeStatus status = classify(worker, scheduler);
            return (status == RuntimeStatus.READY ? Health.up() : Health.down())
                    .withDetails(details(worker, scheduler, status)).build();
        } catch (RuntimeException unavailable) {
            return Health.down()
                    .withDetail("schemaVersion", SnapshotSchema.VERSION)
                    .withDetail("runtimeStatus", RuntimeStatus.UNAVAILABLE.name())
                    .build();
        }
    }

    private static RuntimeStatus classify(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.RuntimeSnapshot worker,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler.Snapshot scheduler) {
        if (worker.closed() || scheduler.closed()) {
            return RuntimeStatus.CLOSED;
        }
        if (scheduler.overdue()) {
            return RuntimeStatus.SCHEDULER_STALLED;
        }
        if (scheduler.lastPollFailed()) {
            return RuntimeStatus.SCHEDULER_FAILED;
        }
        if (worker.lastCycleFailed()) {
            return RuntimeStatus.CYCLE_FAILED;
        }
        if (scheduler.latestCycleHadLaneFailures()
                || worker.lastCompletedCycleHadLaneFailures()) {
            return RuntimeStatus.LANE_FAILURES;
        }
        return RuntimeStatus.READY;
    }

    private static Map<String, Object> details(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.RuntimeSnapshot worker,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler.Snapshot scheduler,
            RuntimeStatus status) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("schemaVersion", SnapshotSchema.VERSION);
        details.put("runtimeStatus", status.name());
        details.put("schedulerActive", scheduler.active());
        details.put("schedulerOverdue", scheduler.overdue());
        details.put("schedulerPollCount", scheduler.pollCount());
        details.put("schedulerCompletedPollCount", scheduler.completedPollCount());
        details.put("schedulerPollFailureCount", scheduler.pollFailureCount());
        details.put("schedulerLastPollFailed", scheduler.lastPollFailed());
        details.put("pollIntervalMillis", scheduler.pollIntervalMillis());
        details.put("maximumCycleDurationMillis",
                scheduler.maximumCycleDurationMillis());
        details.put("latestInventoryGeneration", scheduler.latestInventoryGeneration());
        details.put("latestAttemptedLanes", scheduler.latestAttemptedLanes());
        details.put("latestAcquiredLanes", scheduler.latestAcquiredLanes());
        details.put("latestFailedLanes", scheduler.latestFailedLanes());
        details.put("workerActive", worker.active());
        details.put("workerCycleCount", worker.cycleCount());
        details.put("workerCycleFailureCount", worker.cycleFailureCount());
        details.put("workerLaneAttemptCount", worker.laneAttemptCount());
        details.put("workerLaneAcquiredCount", worker.laneAcquiredCount());
        details.put("workerLaneFailureCount", worker.laneFailureCount());
        return Map.copyOf(details);
    }

    /** Bounded readiness classification without fleet or lane identity. */
    public enum RuntimeStatus {
        /** The local fleet scheduler can safely poll authorized recovery lanes. */
        READY,

        /** The scheduler or worker lifecycle has closed. */
        CLOSED,

        /** The timer stopped making progress or an active cycle exceeded its health budget. */
        SCHEDULER_STALLED,

        /** The latest scheduler poll terminated by runtime or fatal failure. */
        SCHEDULER_FAILED,

        /** The worker rejected inventory or another cycle-wide invariant failed. */
        CYCLE_FAILED,

        /** The latest completed cycle isolated one or more lane runtime failures. */
        LANE_FAILURES,

        /** Runtime snapshots could not be read safely. */
        UNAVAILABLE
    }

    private static final class SnapshotSchema {
        private static final String VERSION =
                "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetHealth.v1";

        private SnapshotSchema() {
        }
    }
}
