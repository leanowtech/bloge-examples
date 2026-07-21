package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootPublicationService.ExecutionStatus;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Aggregate-only Actuator health for one bootstrap-root publication runtime.
 *
 * <p>Readiness is derived from the latest local scheduler outcome, static response-key lifecycle,
 * durable quarantine observations, and fixed publisher-call capacity. The projection deliberately
 * omits scope, root set, worker, publication, endpoint, key identity, fingerprints, payloads, and
 * exception text.</p>
 *
 * <p>An unverified but currently usable static response key is locally ready: the publisher cannot
 * manufacture a harmless probe without creating a publication intent. A real request still has to
 * pass the full signed-response protocol before it can complete.</p>
 */
public final class ExternalSequenceAnchorBootstrapRootPublicationHealth
        implements HealthIndicator {

    private final Supplier<ExternalSequenceAnchorBootstrapRootPublicationService.Snapshot>
            serviceSnapshot;
    private final Supplier<ExternalSequenceAnchorBootstrapRootPublicationScheduler.Snapshot>
            schedulerSnapshot;

    /**
     * Creates health over one Spring-owned service and its one-lane scheduler.
     *
     * @param service database-fenced publication service
     * @param scheduler process-local publication wake-up lane
     */
    public ExternalSequenceAnchorBootstrapRootPublicationHealth(
            ExternalSequenceAnchorBootstrapRootPublicationService service,
            ExternalSequenceAnchorBootstrapRootPublicationScheduler scheduler) {
        this(Objects.requireNonNull(service, "service")::snapshot,
                Objects.requireNonNull(scheduler, "scheduler")::snapshot);
    }

    /** Package-visible deterministic snapshot seam for health-state tests. */
    ExternalSequenceAnchorBootstrapRootPublicationHealth(
            Supplier<ExternalSequenceAnchorBootstrapRootPublicationService.Snapshot>
                    serviceSnapshot,
            Supplier<ExternalSequenceAnchorBootstrapRootPublicationScheduler.Snapshot>
                    schedulerSnapshot) {
        this.serviceSnapshot = Objects.requireNonNull(serviceSnapshot, "serviceSnapshot");
        this.schedulerSnapshot = Objects.requireNonNull(
                schedulerSnapshot, "schedulerSnapshot");
    }

    /**
     * Returns UP only while the local key, scheduler, database control result, and call capacity
     * remain usable.
     *
     * @return payload-free Actuator health
     */
    @Override
    public Health health() {
        try {
            var service = serviceSnapshot.get();
            var scheduler = schedulerSnapshot.get();
            RuntimeStatus status = classify(service, scheduler);
            Map<String, Object> details = details(service, scheduler, status);
            return (status == RuntimeStatus.READY ? Health.up() : Health.down())
                    .withDetails(details).build();
        } catch (RuntimeException unavailable) {
            return Health.down()
                    .withDetail("schemaVersion", SnapshotSchema.VERSION)
                    .withDetail("runtimeStatus", RuntimeStatus.UNAVAILABLE.name())
                    .build();
        }
    }

    private static RuntimeStatus classify(
            ExternalSequenceAnchorBootstrapRootPublicationService.Snapshot service,
            ExternalSequenceAnchorBootstrapRootPublicationScheduler.Snapshot scheduler) {
        if (service.closed() || service.supervisor().closed() || scheduler.closed()) {
            return RuntimeStatus.CLOSED;
        }
        if (scheduler.lastPollFailed()) {
            return RuntimeStatus.SCHEDULER_FAILED;
        }
        ExecutionStatus lastStatus = scheduler.lastStatus();
        if (lastStatus != null) {
            RuntimeStatus terminal = switch (lastStatus) {
                case AUTHENTICATED_CONFLICT, QUARANTINED -> RuntimeStatus.QUARANTINED;
                case ATTEMPT_LIMIT_REACHED -> RuntimeStatus.ATTEMPT_LIMIT_REACHED;
                case CONTROL_UNAVAILABLE, RECEIPT_CONFLICT ->
                        RuntimeStatus.CONTROL_UNAVAILABLE;
                case PUBLISHER_UNAVAILABLE -> RuntimeStatus.PUBLISHER_UNAVAILABLE;
                case RESPONSE_INVALID -> RuntimeStatus.RESPONSE_INVALID;
                default -> null;
            };
            if (terminal != null) {
                return terminal;
            }
        }
        if (!service.descriptor().available()) {
            return RuntimeStatus.KEY_UNAVAILABLE;
        }
        if (service.supervisor().activeCalls()
                == service.supervisor().policy().maximumConcurrentCalls()
                && service.supervisor().lingeringCalls()
                == service.supervisor().activeCalls()) {
            return RuntimeStatus.CALL_CAPACITY_EXHAUSTED;
        }
        return RuntimeStatus.READY;
    }

    private static Map<String, Object> details(
            ExternalSequenceAnchorBootstrapRootPublicationService.Snapshot service,
            ExternalSequenceAnchorBootstrapRootPublicationScheduler.Snapshot scheduler,
            RuntimeStatus status) {
        var supervisor = service.supervisor();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("schemaVersion", SnapshotSchema.VERSION);
        details.put("runtimeStatus", status.name());
        details.put("publisherAvailable", service.descriptor().available());
        details.put("publisherStatus", service.publisher().status());
        details.put("transportSystemTrustStore", service.transport().systemTrustStore());
        details.put("transportPrivateTrustStore", service.transport().privateTrustStore());
        details.put("transportServerSpkiPinned", service.transport().serverSpkiPinned());
        details.put("transportMutualTls", service.transport().mutualTls());
        details.put("schedulerActive", scheduler.active());
        details.put("schedulerPollCount", scheduler.pollCount());
        details.put("schedulerCompletionCount", scheduler.completionCount());
        details.put("schedulerQuarantineCount", scheduler.quarantineCount());
        details.put("schedulerBoundedFailureCount", scheduler.boundedFailureCount());
        details.put("schedulerPollFailureCount", scheduler.pollFailureCount());
        details.put("schedulerLastPollFailed", scheduler.lastPollFailed());
        details.put("callCapacity", supervisor.policy().maximumConcurrentCalls());
        details.put("activeCalls", supervisor.activeCalls());
        details.put("lingeringCalls", supervisor.lingeringCalls());
        details.put("timedOutCalls", supervisor.timedOutCalls());
        details.put("saturatedCalls", supervisor.saturatedCalls());
        return Map.copyOf(details);
    }

    /** Bounded readiness classification without deployment or publication identity. */
    public enum RuntimeStatus {
        /** The configured key and local execution path can accept durable work. */
        READY,

        /** One or more runtime owners have closed. */
        CLOSED,

        /** The latest scheduler poll threw before returning a bounded result. */
        SCHEDULER_FAILED,

        /** An authenticated remote conflict permanently blocks this root set. */
        QUARANTINED,

        /** The oldest publication exhausted its automatic attempt budget. */
        ATTEMPT_LIMIT_REACHED,

        /** Database control or exact receipt consistency is unavailable. */
        CONTROL_UNAVAILABLE,

        /** The configured publisher transport or adapter is unavailable. */
        PUBLISHER_UNAVAILABLE,

        /** The latest publisher response failed strict authentication or binding. */
        RESPONSE_INVALID,

        /** The statically configured response key is outside its usable lifecycle. */
        KEY_UNAVAILABLE,

        /** Every fixed call slot is occupied by an interruption-ignoring call. */
        CALL_CAPACITY_EXHAUSTED,

        /** Health snapshots could not be read safely. */
        UNAVAILABLE
    }

    private static final class SnapshotSchema {
        private static final String VERSION =
                "bloge.externalSequenceAnchorBootstrapRootPublicationHealth.v1";

        private SnapshotSchema() {
        }
    }
}
