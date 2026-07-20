package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyService.ExecutionStatus;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyService.RecoveryStatus;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Aggregate-only Actuator health for one bootstrap-root ceremony recovery lane.
 *
 * <p>The indicator reads only process-local service and scheduler snapshots. It never acquires a
 * durable attempt, resolves an authority, invokes a signer, or probes a provider. Details omit
 * scope, root set, worker, ceremony, authority, endpoint, key, fingerprint, payload, and exception
 * text.</p>
 *
 * <p>No active ceremony, an approval wait, a live competing lease, and durable retry backoff are
 * healthy workflow states. An exhausted automatic budget, the latest scheduler failure,
 * a failed acquired execution, or fully lingering signer capacity fails readiness.</p>
 */
public final class ExternalSequenceAnchorBootstrapRootCeremonyRecoveryHealth
        implements HealthIndicator {

    private final Supplier<ExternalSequenceAnchorBootstrapRootCeremonyService.RuntimeSnapshot>
            serviceSnapshot;
    private final Supplier<ExternalSequenceAnchorBootstrapRootCeremonyRecoveryScheduler.Snapshot>
            schedulerSnapshot;

    /**
     * Creates health over one Spring-owned recovery service and scheduler.
     *
     * @param service durable ceremony coordinator
     * @param scheduler process-local recovery wake-up lane
     */
    public ExternalSequenceAnchorBootstrapRootCeremonyRecoveryHealth(
            ExternalSequenceAnchorBootstrapRootCeremonyService service,
            ExternalSequenceAnchorBootstrapRootCeremonyRecoveryScheduler scheduler) {
        this(Objects.requireNonNull(service, "service")::runtimeSnapshot,
                Objects.requireNonNull(scheduler, "scheduler")::snapshot);
    }

    /** Package-visible deterministic snapshot seam for health-state tests. */
    ExternalSequenceAnchorBootstrapRootCeremonyRecoveryHealth(
            Supplier<ExternalSequenceAnchorBootstrapRootCeremonyService.RuntimeSnapshot>
                    serviceSnapshot,
            Supplier<ExternalSequenceAnchorBootstrapRootCeremonyRecoveryScheduler.Snapshot>
                    schedulerSnapshot) {
        this.serviceSnapshot = Objects.requireNonNull(serviceSnapshot, "serviceSnapshot");
        this.schedulerSnapshot = Objects.requireNonNull(
                schedulerSnapshot, "schedulerSnapshot");
    }

    /**
     * Returns UP only while lifecycle, scheduler, latest execution, and signer capacity are usable.
     *
     * @return payload-free Actuator health
     */
    @Override
    public Health health() {
        try {
            var service = serviceSnapshot.get();
            var scheduler = schedulerSnapshot.get();
            RuntimeStatus status = classify(service, scheduler);
            return (status == RuntimeStatus.READY ? Health.up() : Health.down())
                    .withDetails(details(service, scheduler, status)).build();
        } catch (RuntimeException unavailable) {
            return Health.down()
                    .withDetail("schemaVersion", SnapshotSchema.VERSION)
                    .withDetail("runtimeStatus", RuntimeStatus.UNAVAILABLE.name())
                    .build();
        }
    }

    private static RuntimeStatus classify(
            ExternalSequenceAnchorBootstrapRootCeremonyService.RuntimeSnapshot service,
            ExternalSequenceAnchorBootstrapRootCeremonyRecoveryScheduler.Snapshot scheduler) {
        var signer = service.signerCalls();
        if (service.closed() || signer.closed() || scheduler.closed()) {
            return RuntimeStatus.CLOSED;
        }
        if (scheduler.lastPollFailed()) {
            return RuntimeStatus.SCHEDULER_FAILED;
        }
        if (scheduler.lastStatus() == RecoveryStatus.ATTEMPT_LIMIT_REACHED) {
            return RuntimeStatus.ATTEMPT_LIMIT_REACHED;
        }
        ExecutionStatus execution = scheduler.lastExecutionStatus();
        if (execution != null) {
            RuntimeStatus failure = switch (execution) {
                case PRODUCED, IDEMPOTENT_REPLAY -> null;
                case FAILED, EXPIRED -> RuntimeStatus.EXECUTION_FAILED;
                case FENCE_REJECTED -> RuntimeStatus.FENCE_REJECTED;
                case NOT_APPROVED, BUSY, NOT_FOUND -> RuntimeStatus.EXECUTION_REJECTED;
            };
            if (failure != null) {
                return failure;
            }
        }
        if (signer.activeCalls() == signer.policy().maximumConcurrentCalls()
                && signer.lingeringCalls() == signer.activeCalls()) {
            return RuntimeStatus.SIGNER_CAPACITY_EXHAUSTED;
        }
        return RuntimeStatus.READY;
    }

    private static Map<String, Object> details(
            ExternalSequenceAnchorBootstrapRootCeremonyService.RuntimeSnapshot service,
            ExternalSequenceAnchorBootstrapRootCeremonyRecoveryScheduler.Snapshot scheduler,
            RuntimeStatus status) {
        var signer = service.signerCalls();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("schemaVersion", SnapshotSchema.VERSION);
        details.put("runtimeStatus", status.name());
        details.put("workflowStatus", scheduler.lastStatus() == null
                ? "NOT_POLLED" : scheduler.lastStatus().name());
        details.put("executionStatus", scheduler.lastExecutionStatus() == null
                ? "NOT_EXECUTED" : scheduler.lastExecutionStatus().name());
        details.put("schedulerActive", scheduler.active());
        details.put("schedulerPollCount", scheduler.pollCount());
        details.put("schedulerExecutedCount", scheduler.executedCount());
        details.put("schedulerPollFailureCount", scheduler.pollFailureCount());
        details.put("schedulerLastPollFailed", scheduler.lastPollFailed());
        details.put("signerCallCapacity", signer.policy().maximumConcurrentCalls());
        details.put("activeSignerCalls", signer.activeCalls());
        details.put("lingeringSignerCalls", signer.lingeringCalls());
        details.put("timedOutSignerCalls", signer.timedOutCalls());
        details.put("saturatedSignerCalls", signer.saturatedCalls());
        details.put("failedSignerCalls", signer.failedCalls());
        return Map.copyOf(details);
    }

    /** Bounded readiness classification without ceremony or signer identity. */
    public enum RuntimeStatus {
        /** The local recovery lane can safely inspect or acquire durable work. */
        READY,

        /** The service, signer supervisor, or scheduler has closed. */
        CLOSED,

        /** The latest scheduler poll threw before returning a bounded result. */
        SCHEDULER_FAILED,

        /** Durable automatic execution attempts are exhausted. */
        ATTEMPT_LIMIT_REACHED,

        /** The latest acquired attempt failed or expired before completion. */
        EXECUTION_FAILED,

        /** The latest acquired attempt lost its database execution fence. */
        FENCE_REJECTED,

        /** Recovery returned an execution status that is invalid for an acquired attempt. */
        EXECUTION_REJECTED,

        /** Every fixed signer slot is occupied by an interruption-ignoring adapter. */
        SIGNER_CAPACITY_EXHAUSTED,

        /** Runtime snapshots could not be read safely. */
        UNAVAILABLE
    }

    private static final class SnapshotSchema {
        private static final String VERSION =
                "bloge.externalSequenceAnchorBootstrapRootCeremonyRecoveryHealth.v1";

        private SnapshotSchema() {
        }
    }
}
