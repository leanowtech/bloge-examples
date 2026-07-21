package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalSequenceAnchorBootstrapRootPublicationHealthTest {

    @Test
    void usableUnverifiedStaticKeyIsReadyWithoutRemoteProbe() {
        var health = health(service(true, 0, 0, false), scheduler(null, false)).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("runtimeStatus", "READY")
                .doesNotContainKeys("scopeId", "rootSetId", "workerId", "endpoint",
                        "publisherId", "keyId", "fingerprint", "error");
    }

    @Test
    void authenticatedConflictAndAttemptExhaustionFailReadiness() {
        var quarantined = health(service(true, 0, 0, false),
                scheduler(ExternalSequenceAnchorBootstrapRootPublicationService.ExecutionStatus
                        .AUTHENTICATED_CONFLICT, false)).health();
        var exhausted = health(service(true, 0, 0, false),
                scheduler(ExternalSequenceAnchorBootstrapRootPublicationService.ExecutionStatus
                        .ATTEMPT_LIMIT_REACHED, false)).health();

        assertThat(quarantined.getStatus()).isEqualTo(Status.DOWN);
        assertThat(quarantined.getDetails()).containsEntry("runtimeStatus", "QUARANTINED");
        assertThat(exhausted.getStatus()).isEqualTo(Status.DOWN);
        assertThat(exhausted.getDetails())
                .containsEntry("runtimeStatus", "ATTEMPT_LIMIT_REACHED");
    }

    @Test
    void latestSchedulerExceptionFailsReadinessEvenAfterAnEarlierSuccess() {
        var result = health(service(true, 0, 0, false), scheduler(
                ExternalSequenceAnchorBootstrapRootPublicationService.ExecutionStatus.PUBLISHED,
                true)).health();

        assertThat(result.getStatus()).isEqualTo(Status.DOWN);
        assertThat(result.getDetails()).containsEntry("runtimeStatus", "SCHEDULER_FAILED")
                .containsEntry("schedulerLastPollFailed", true);
    }

    @Test
    void unusableKeyAndFullyLingeringCapacityHaveDistinctBoundedStates() {
        var keyUnavailable = health(service(false, 0, 0, false),
                scheduler(null, false)).health();
        var saturated = health(service(true, 2, 2, false), scheduler(null, false)).health();

        assertThat(keyUnavailable.getDetails())
                .containsEntry("runtimeStatus", "KEY_UNAVAILABLE");
        assertThat(saturated.getDetails())
                .containsEntry("runtimeStatus", "CALL_CAPACITY_EXHAUSTED");
    }

    @Test
    void snapshotFailureReturnsOnlyAStableUnavailableClassification() {
        var indicator = new ExternalSequenceAnchorBootstrapRootPublicationHealth(
                () -> { throw new IllegalStateException("provider-sensitive-diagnostics"); },
                () -> scheduler(null, false));

        var health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("runtimeStatus", "UNAVAILABLE");
        assertThat(health.getDetails().toString())
                .doesNotContain("provider-sensitive-diagnostics");
    }

    @SuppressWarnings("deprecation")
    @Test
    void v1JavaSnapshotConstructionUpgradesToExplicitSystemTrustTruth() {
        var current = service(true, 0, 0, false);

        var upgraded = new ExternalSequenceAnchorBootstrapRootPublicationService.Snapshot(
                ExternalSequenceAnchorBootstrapRootPublicationService.Snapshot.SCHEMA_VERSION_V1,
                current.closed(), current.descriptor(), current.publisher(),
                current.supervisor());

        assertThat(upgraded.schemaVersion()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootPublicationService.Snapshot.SCHEMA_VERSION);
        assertThat(upgraded.transport()).satisfies(transport -> {
            assertThat(transport.systemTrustStore()).isTrue();
            assertThat(transport.serverSpkiPinned()).isFalse();
            assertThat(transport.mutualTls()).isFalse();
        });
    }

    private static ExternalSequenceAnchorBootstrapRootPublicationHealth health(
            ExternalSequenceAnchorBootstrapRootPublicationService.Snapshot service,
            ExternalSequenceAnchorBootstrapRootPublicationScheduler.Snapshot scheduler) {
        return new ExternalSequenceAnchorBootstrapRootPublicationHealth(
                () -> service, () -> scheduler);
    }

    private static ExternalSequenceAnchorBootstrapRootPublicationService.Snapshot service(
            boolean available, long activeCalls, long lingeringCalls, boolean closed) {
        var descriptor = new ExternalSequenceAnchorBootstrapRootPublisher.Descriptor(
                ExternalSequenceAnchorBootstrapRootPublisher.Descriptor.SCHEMA_VERSION,
                available, true, true, true, true, true, 1024);
        var publisher = new ExternalSequenceAnchorBootstrapRootPublisher.Snapshot(
                ExternalSequenceAnchorBootstrapRootPublisher.Snapshot.SCHEMA_VERSION,
                available, available ? "UNVERIFIED" : "KEY_UNAVAILABLE",
                0, 0, 0, 0, null);
        var policy = new ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor.Policy(
                Duration.ofSeconds(4), 2);
        var supervisor = new ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor.Snapshot(
                ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor.Snapshot
                        .SCHEMA_VERSION,
                policy, activeCalls, 0, 0, 0, 0, lingeringCalls, 0, 0, 0,
                activeCalls, lingeringCalls, closed);
        return new ExternalSequenceAnchorBootstrapRootPublicationService.Snapshot(
                ExternalSequenceAnchorBootstrapRootPublicationService.Snapshot.SCHEMA_VERSION,
                closed, descriptor,
                new ControlPlaneHttpTransport.Descriptor(
                        ControlPlaneHttpTransport.Descriptor.SCHEMA_VERSION,
                        false, true, true, true),
                publisher, supervisor);
    }

    private static ExternalSequenceAnchorBootstrapRootPublicationScheduler.Snapshot scheduler(
            ExternalSequenceAnchorBootstrapRootPublicationService.ExecutionStatus status,
            boolean lastPollFailed) {
        long pollCount = status == null && !lastPollFailed ? 0 : 1;
        long completionCount = status
                == ExternalSequenceAnchorBootstrapRootPublicationService.ExecutionStatus.PUBLISHED
                ? 1 : 0;
        return new ExternalSequenceAnchorBootstrapRootPublicationScheduler.Snapshot(
                ExternalSequenceAnchorBootstrapRootPublicationScheduler.Snapshot.SCHEMA_VERSION,
                false, false, pollCount, completionCount,
                status == ExternalSequenceAnchorBootstrapRootPublicationService.ExecutionStatus
                        .AUTHENTICATED_CONFLICT ? 1 : 0,
                0, lastPollFailed ? 1 : 0, lastPollFailed, status);
    }
}
