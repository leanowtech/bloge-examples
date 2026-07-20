package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalSequenceAnchorBootstrapRootCeremonyRecoveryHealthTest {

    @Test
    void noWorkApprovalWaitAndRetryDelayRemainHealthyWorkflowStates() {
        var noWork = health(service(0, 0, false), scheduler(
                ExternalSequenceAnchorBootstrapRootCeremonyService.RecoveryStatus
                        .NO_ACTIVE_CEREMONY, null, false)).health();
        var approval = health(service(0, 0, false), scheduler(
                ExternalSequenceAnchorBootstrapRootCeremonyService.RecoveryStatus
                        .AWAITING_APPROVAL, null, false)).health();
        var delayed = health(service(0, 0, false), scheduler(
                ExternalSequenceAnchorBootstrapRootCeremonyService.RecoveryStatus
                        .RETRY_DELAYED, null, false)).health();

        assertThat(noWork.getStatus()).isEqualTo(Status.UP);
        assertThat(approval.getStatus()).isEqualTo(Status.UP);
        assertThat(delayed.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void exhaustedBudgetAndFailedExecutionHaveDistinctDownStates() {
        var exhausted = health(service(0, 0, false), scheduler(
                ExternalSequenceAnchorBootstrapRootCeremonyService.RecoveryStatus
                        .ATTEMPT_LIMIT_REACHED, null, false)).health();
        var failed = health(service(0, 0, false), scheduler(
                ExternalSequenceAnchorBootstrapRootCeremonyService.RecoveryStatus.EXECUTED,
                ExternalSequenceAnchorBootstrapRootCeremonyService.ExecutionStatus.FAILED,
                false)).health();

        assertThat(exhausted.getStatus()).isEqualTo(Status.DOWN);
        assertThat(exhausted.getDetails())
                .containsEntry("runtimeStatus", "ATTEMPT_LIMIT_REACHED");
        assertThat(failed.getStatus()).isEqualTo(Status.DOWN);
        assertThat(failed.getDetails())
                .containsEntry("runtimeStatus", "EXECUTION_FAILED");
    }

    @Test
    void latestSchedulerExceptionOverridesAnEarlierSuccessfulExecution() {
        var result = health(service(0, 0, false), scheduler(
                ExternalSequenceAnchorBootstrapRootCeremonyService.RecoveryStatus.EXECUTED,
                ExternalSequenceAnchorBootstrapRootCeremonyService.ExecutionStatus.PRODUCED,
                true)).health();

        assertThat(result.getStatus()).isEqualTo(Status.DOWN);
        assertThat(result.getDetails()).containsEntry("runtimeStatus", "SCHEDULER_FAILED")
                .containsEntry("schedulerLastPollFailed", true);
    }

    @Test
    void fullyLingeringSignerCapacityFailsWithoutExposingSignerIdentity() {
        var result = health(service(2, 2, false), scheduler(null, null, false)).health();

        assertThat(result.getStatus()).isEqualTo(Status.DOWN);
        assertThat(result.getDetails())
                .containsEntry("runtimeStatus", "SIGNER_CAPACITY_EXHAUSTED")
                .doesNotContainKeys("scopeId", "rootSetId", "workerId", "ceremonyId",
                        "authorityId", "endpoint", "keyId", "fingerprint", "error");
    }

    @Test
    void closedRuntimeAndSnapshotFailureReturnOnlyStableClassifications() {
        var closed = health(service(0, 0, true), scheduler(null, null, false)).health();
        var unavailable = new ExternalSequenceAnchorBootstrapRootCeremonyRecoveryHealth(
                () -> { throw new IllegalStateException("provider-sensitive-diagnostics"); },
                () -> scheduler(null, null, false)).health();

        assertThat(closed.getDetails()).containsEntry("runtimeStatus", "CLOSED");
        assertThat(unavailable.getStatus()).isEqualTo(Status.DOWN);
        assertThat(unavailable.getDetails()).containsEntry("runtimeStatus", "UNAVAILABLE");
        assertThat(unavailable.getDetails().toString())
                .doesNotContain("provider-sensitive-diagnostics");
    }

    private static ExternalSequenceAnchorBootstrapRootCeremonyRecoveryHealth health(
            ExternalSequenceAnchorBootstrapRootCeremonyService.RuntimeSnapshot service,
            ExternalSequenceAnchorBootstrapRootCeremonyRecoveryScheduler.Snapshot scheduler) {
        return new ExternalSequenceAnchorBootstrapRootCeremonyRecoveryHealth(
                () -> service, () -> scheduler);
    }

    private static ExternalSequenceAnchorBootstrapRootCeremonyService.RuntimeSnapshot service(
            long activeCalls,
            long lingeringCalls,
            boolean closed) {
        var policy = new ExternalSequenceAnchorBootstrapRootSignerCallSupervisor.Policy(
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), 2);
        var signer = new ExternalSequenceAnchorBootstrapRootSignerCallSupervisor.Snapshot(
                ExternalSequenceAnchorBootstrapRootSignerCallSupervisor.Snapshot.SCHEMA_VERSION,
                policy, activeCalls, 0, 0, 0, 0, 0, 0,
                activeCalls, lingeringCalls, closed);
        return new ExternalSequenceAnchorBootstrapRootCeremonyService.RuntimeSnapshot(
                ExternalSequenceAnchorBootstrapRootCeremonyService.RuntimeSnapshot
                        .SCHEMA_VERSION,
                closed, signer);
    }

    private static ExternalSequenceAnchorBootstrapRootCeremonyRecoveryScheduler.Snapshot
            scheduler(
            ExternalSequenceAnchorBootstrapRootCeremonyService.RecoveryStatus status,
            ExternalSequenceAnchorBootstrapRootCeremonyService.ExecutionStatus execution,
            boolean lastPollFailed) {
        long polls = status == null && !lastPollFailed ? 0L : 1L;
        return new ExternalSequenceAnchorBootstrapRootCeremonyRecoveryScheduler.Snapshot(
                ExternalSequenceAnchorBootstrapRootCeremonyRecoveryScheduler.Snapshot
                        .SCHEMA_VERSION,
                false, false, polls,
                status == ExternalSequenceAnchorBootstrapRootCeremonyService.RecoveryStatus
                        .EXECUTED ? 1L : 0L,
                lastPollFailed ? 1L : 0L, lastPollFailed, status, execution);
    }
}
