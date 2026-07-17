package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestRecoveryAuthorization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DurableTestWorkerAcquisitionServiceTest {

    private static final String SHA_A = "sha256:" + "a".repeat(64);
    private static final String SHA_B = "sha256:" + "b".repeat(64);

    private DurableTestExecutionCheckpointRepository checkpoints;
    private DurableTestRecoveryAuthorizer authorizer;
    private TestSecurityEventRepository securityEvents;
    private DurableTestWorkerAcquisitionService service;

    @BeforeEach
    void setUp() {
        checkpoints = mock(DurableTestExecutionCheckpointRepository.class);
        authorizer = mock(DurableTestRecoveryAuthorizer.class);
        securityEvents = mock(TestSecurityEventRepository.class);
        service = new DurableTestWorkerAcquisitionService(
                checkpoints, authorizer, securityEvents,
                new ObjectMapper().findAndRegisterModules(), "worker-instance-a",
                Duration.ofMinutes(2), 32);
        when(securityEvents.boundAppend(any())).thenReturn(TestRuntimeTransactionMutation.noop());
    }

    @Test
    void replaysCommittedAssignmentBeforeQueueScanOrDependencyAuthorization() {
        DurableTestExecutionCheckpoint assigned = checkpoint("run-a", SHA_A);
        var prior = result(assigned, true);
        when(checkpoints.findWorkerAcquisitionResult(any(), eq("poll-1"), any()))
                .thenReturn(Optional.of(prior));
        when(securityEvents.append(any())).thenAnswer(call -> call.getArgument(0));

        DurableTestWorkerAcquisitionResponse response = service.acquire(
                request("poll-1"), identity());

        assertThat(response.outcome()).isEqualTo("ACQUIRED");
        assertThat(response.idempotentReplay()).isTrue();
        assertThat(response.assignment().runId()).isEqualTo("run-a");
        verify(checkpoints, never()).findExpiredRecoveryCandidates(any());
        verifyNoInteractions(authorizer);
    }

    @Test
    void skipsIneligibleCandidateAndClaimsNextAuthorizedExactFence() {
        DurableTestExecutionCheckpoint unavailable = checkpoint("run-old", SHA_A);
        DurableTestExecutionCheckpoint eligible = checkpoint("run-next", SHA_B);
        when(checkpoints.findWorkerAcquisitionResult(any(), eq("poll-1"), any()))
                .thenReturn(Optional.empty());
        when(checkpoints.findExpiredRecoveryCandidates(any()))
                .thenReturn(List.of(unavailable, eligible));
        when(authorizer.authorize(eq(unavailable), any())).thenThrow(
                new IntegrationProblemException(
                        com.leanowtech.bloge.gateway.integration.IntegrationProblem.conflict(
                                "RG.TEST.DURABLE_CONTROL_PLAN_UNAVAILABLE", "gone", "c", java.util.Map.of())));
        DurableTestRecoveryAuthorizer.AuthorizedRecovery authorized = authorized(eligible);
        when(authorizer.authorize(eq(eligible), any())).thenReturn(authorized);
        var acquired = result(eligible, false);
        when(checkpoints.acquireWorkerCommandIdempotently(
                any(), any(), any())).thenReturn(acquired);

        DurableTestWorkerAcquisitionResponse response = service.acquire(
                request("poll-1"), identity());

        assertThat(response.assignment().runId()).isEqualTo("run-next");
        ArgumentCaptor<Optional<DurableTestExecutionCheckpointRepository.WorkerAcquisitionSelection>>
                selection = ArgumentCaptor.forClass(Optional.class);
        verify(checkpoints).acquireWorkerCommandIdempotently(
                any(), selection.capture(), any());
        assertThat(selection.getValue()).isPresent();
        assertThat(selection.getValue().orElseThrow().claim().runId()).isEqualTo("run-next");
        assertThat(selection.getValue().orElseThrow().claim().claimantOwnerId())
                .isEqualTo("worker-instance-a");
    }

    @Test
    void commitsBoundedNoWorkResultWithNoCallerSelectedQueueFacts() {
        when(checkpoints.findWorkerAcquisitionResult(any(), eq("poll-1"), any()))
                .thenReturn(Optional.empty());
        when(checkpoints.findExpiredRecoveryCandidates(any())).thenReturn(List.of());
        var noWork = mock(
                DurableTestExecutionCheckpointRepository.WorkerAcquisitionResult.class);
        when(noWork.outcome()).thenReturn(
                DurableTestExecutionCheckpointRepository.WorkerAcquisitionOutcome.NO_WORK);
        when(noWork.observedAt()).thenReturn(Instant.parse("2026-07-17T00:00:00Z"));
        when(noWork.idempotentReplay()).thenReturn(false);
        when(checkpoints.acquireWorkerCommandIdempotently(any(), eq(Optional.empty()), any()))
                .thenReturn(noWork);

        DurableTestWorkerAcquisitionResponse response = service.acquire(
                request("poll-1"), identity());

        assertThat(response.outcome()).isEqualTo("NO_WORK");
        assertThat(response.assignment()).isNull();
        ArgumentCaptor<DurableTestExecutionCheckpointRepository.RecoveryCandidateQuery> query =
                ArgumentCaptor.forClass(
                        DurableTestExecutionCheckpointRepository.RecoveryCandidateQuery.class);
        verify(checkpoints).findExpiredRecoveryCandidates(query.capture());
        assertThat(query.getValue().limit()).isEqualTo(32);
        assertThat(query.getValue().scope().projectId()).isEqualTo("project-a");
        verifyNoInteractions(authorizer);
    }

    @Test
    void staleCandidateRaceContinuesToAnotherAssignment() {
        DurableTestExecutionCheckpoint first = checkpoint("run-first", SHA_A);
        DurableTestExecutionCheckpoint second = checkpoint("run-second", SHA_B);
        when(checkpoints.findWorkerAcquisitionResult(any(), eq("poll-1"), any()))
                .thenReturn(Optional.empty());
        when(checkpoints.findExpiredRecoveryCandidates(any()))
                .thenReturn(List.of(first, second));
        DurableTestRecoveryAuthorizer.AuthorizedRecovery firstAuthorization = authorized(first);
        DurableTestRecoveryAuthorizer.AuthorizedRecovery secondAuthorization = authorized(second);
        when(authorizer.authorize(any(), any()))
                .thenReturn(firstAuthorization, secondAuthorization);
        when(checkpoints.acquireWorkerCommandIdempotently(any(), any(), any()))
                .thenThrow(new DurableTestExecutionCheckpointConflictException(
                        DurableTestExecutionCheckpointConflictException.Reason.STALE_FENCE,
                        "lost race"))
                .thenReturn(result(second, false));

        DurableTestWorkerAcquisitionResponse response = service.acquire(
                request("poll-1"), identity());

        assertThat(response.assignment().runId()).isEqualTo("run-second");
        verify(checkpoints, org.mockito.Mockito.times(2))
                .acquireWorkerCommandIdempotently(any(), any(), any());
    }

    @Test
    void dependencyInfrastructureFailureDoesNotCommitAFalseNoWorkResult() {
        DurableTestExecutionCheckpoint candidate = checkpoint("run-a", SHA_A);
        when(checkpoints.findWorkerAcquisitionResult(any(), eq("poll-1"), any()))
                .thenReturn(Optional.empty());
        when(checkpoints.findExpiredRecoveryCandidates(any())).thenReturn(List.of(candidate));
        when(authorizer.authorize(eq(candidate), any())).thenThrow(
                new IllegalStateException("registry offline"));

        assertProblem(() -> service.acquire(request("poll-1"), identity()),
                503, "RG.TEST.DURABLE_AUTHORIZATION_UNAVAILABLE");

        verify(checkpoints, never()).acquireWorkerCommandIdempotently(any(), any(), any());
    }

    @Test
    void rejectsOutOfScopeRepositoryCandidateBeforeAuthorizationOrQueueDisclosure() {
        DurableTestExecutionCheckpoint candidate = checkpoint("run-a", SHA_A);
        when(candidate.scope()).thenReturn(new DurableTestExecutionCheckpoint.Scope(
                "tenant-a", "org-other", "project-other", "test", "runner"));
        when(checkpoints.findWorkerAcquisitionResult(any(), eq("poll-1"), any()))
                .thenReturn(Optional.empty());
        when(checkpoints.findExpiredRecoveryCandidates(any())).thenReturn(List.of(candidate));

        assertProblem(() -> service.acquire(request("poll-1"), identity()),
                503, "RG.TEST.DURABLE_STORE_UNAVAILABLE");

        verifyNoInteractions(authorizer);
        verify(checkpoints, never()).acquireWorkerCommandIdempotently(any(), any(), any());
    }

    @Test
    void rejectsProductionAndMalformedRequestsBeforeQueueAccess() {
        assertProblem(() -> service.acquire(request("poll-1"), identity("production")),
                403, "RG.TEST.DURABLE_ENVIRONMENT_FORBIDDEN");
        assertProblem(() -> service.acquire(
                        new DurableTestWorkerAcquisitionRequest("wrong.version", ""), identity()),
                400, "RG.TEST.DURABLE_WORKER_ACQUISITION_REQUEST_INVALID");

        verifyNoInteractions(checkpoints);
    }

    private static DurableTestRecoveryAuthorizer.AuthorizedRecovery authorized(
            DurableTestExecutionCheckpoint checkpoint) {
        DurableTestRecoveryAuthorizer.AuthorizedRecovery authorized =
                mock(DurableTestRecoveryAuthorizer.AuthorizedRecovery.class);
        DurableTestRecoveryAuthorization authorization = mock(
                DurableTestRecoveryAuthorization.class);
        String checkpointFingerprint = checkpoint.checkpointFingerprint();
        when(authorization.sourceCheckpointFingerprint())
                .thenReturn(checkpointFingerprint);
        when(authorized.authorization()).thenReturn(authorization);
        return authorized;
    }

    private static DurableTestExecutionCheckpointRepository.WorkerAcquisitionResult result(
            DurableTestExecutionCheckpoint checkpoint, boolean replay) {
        var result = mock(
                DurableTestExecutionCheckpointRepository.WorkerAcquisitionResult.class);
        lenient().when(result.outcome()).thenReturn(
                DurableTestExecutionCheckpointRepository.WorkerAcquisitionOutcome.ACQUIRED);
        lenient().when(result.observedAt()).thenReturn(
                Instant.parse("2026-07-17T00:00:00Z"));
        lenient().when(result.checkpoint()).thenReturn(checkpoint);
        lenient().when(result.idempotentReplay()).thenReturn(replay);
        return result;
    }

    private static DurableTestExecutionCheckpoint checkpoint(
            String runId, String fingerprint) {
        DurableTestExecutionCheckpoint checkpoint = mock(DurableTestExecutionCheckpoint.class);
        DurableTestExecutionCheckpoint.ControlDependencies dependencies =
                mock(DurableTestExecutionCheckpoint.ControlDependencies.class);
        lenient().when(checkpoint.schemaVersion())
                .thenReturn(DurableTestExecutionCheckpoint.SCHEMA_VERSION);
        lenient().when(checkpoint.scope()).thenReturn(new DurableTestExecutionCheckpoint.Scope(
                "tenant-a", "org-a", "project-a", "test", "original-runner"));
        lenient().when(checkpoint.runId()).thenReturn(runId);
        lenient().when(checkpoint.checkpointFingerprint()).thenReturn(fingerprint);
        lenient().when(checkpoint.lifecycle()).thenReturn(
                new DurableTestExecutionCheckpoint.Lifecycle(
                        DurableTestExecutionCheckpoint.Status.RESUMING, "expired-owner", 3, 7,
                        Instant.parse("2026-07-16T00:00:00Z"),
                        Instant.parse("2026-07-16T00:01:00Z"),
                        Instant.parse("2026-07-16T00:02:00Z")));
        lenient().when(checkpoint.dependencies()).thenReturn(dependencies);
        lenient().when(dependencies.target()).thenReturn(
                new DurableTestExecutionCheckpoint.ExecutionTargetRef(
                        "GRAPH", "graph-" + runId, SHA_A));
        return checkpoint;
    }

    private static DurableTestWorkerAcquisitionRequest request(String clientRequestId) {
        return new DurableTestWorkerAcquisitionRequest("", clientRequestId);
    }

    private static IntegrationRequestContext identity() {
        return identity("test");
    }

    private static IntegrationRequestContext identity(String environment) {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", environment, "sg", "WORKLOAD",
                "worker-a", "", "TEST_EXECUTION", "correlation-a", Set.of("quality"),
                "CONFIDENTIAL", "");
    }

    private static void assertProblem(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable invocation,
            int status,
            String code) {
        assertThatThrownBy(invocation)
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(error -> {
                    IntegrationProblemException problem = (IntegrationProblemException) error;
                    assertThat(problem.problem().status()).isEqualTo(status);
                    assertThat(problem.problem().code()).isEqualTo(code);
                });
    }
}
