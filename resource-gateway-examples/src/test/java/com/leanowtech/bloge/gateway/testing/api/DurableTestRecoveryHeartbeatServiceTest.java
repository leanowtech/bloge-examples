package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestRecoveryAuthorization;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestRecoveryDispatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DurableTestRecoveryHeartbeatServiceTest {

    private static final String SHA_A = "sha256:" + "a".repeat(64);
    private static final String SHA_B = "sha256:" + "b".repeat(64);

    @Mock
    private DurableTestExecutionCheckpointRepository checkpoints;
    @Mock
    private TestSecurityEventRepository securityEvents;

    private ObjectMapper objectMapper;
    private DurableTestRecoveryHeartbeatService service;
    private TestRuntimeTransactionMutation auditMutation;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        auditMutation = jdbc -> { };
        service = new DurableTestRecoveryHeartbeatService(
                checkpoints, securityEvents, objectMapper, Duration.ofMinutes(2));
    }

    @Test
    void rejectsInvalidServerLeasePolicyAtComposition() {
        assertThatThrownBy(() -> new DurableTestRecoveryHeartbeatService(
                checkpoints, securityEvents, objectMapper, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DurableTestRecoveryHeartbeatService(
                checkpoints, securityEvents, objectMapper, Duration.ofMillis(1500)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DurableTestRecoveryHeartbeatService(
                checkpoints, securityEvents, objectMapper, Duration.ofHours(1).plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void renewsOnlyAnIssuedDispatchForTheSameAuthenticatedPrincipalAndBoundAudit() {
        IntegrationRequestContext identity = identity();
        DurableTestRecoveryDispatch source = dispatch(identity, "org-a", "project-a", 4, 8, SHA_A);
        DurableTestExecutionCheckpoint renewed = checkpoint("org-a", "project-a", 4, 9, SHA_B);
        DurableTestExecutionCheckpointRepository.RecoveryHeartbeatResult result =
                result(renewed, false);
        DurableTestRecoveryHeartbeatRequest request = request("heartbeat-1", source);
        when(checkpoints.findRecoveryDispatch(eq("tenant-a"), eq("test"), eq("run-a"),
                any(), eq(SHA_A))).thenReturn(Optional.of(source));
        when(securityEvents.boundAppend(any())).thenReturn(auditMutation);
        when(checkpoints.heartbeatRecoveryLeaseIdempotently(any(), eq(auditMutation)))
                .thenReturn(result);

        DurableTestRecoveryHeartbeatResponse response =
                service.heartbeat("run-a", request, identity);

        assertThat(response).extracting(
                        DurableTestRecoveryHeartbeatResponse::runId,
                        DurableTestRecoveryHeartbeatResponse::status,
                        DurableTestRecoveryHeartbeatResponse::ownerId,
                        DurableTestRecoveryHeartbeatResponse::leaseEpoch,
                        DurableTestRecoveryHeartbeatResponse::revision,
                        DurableTestRecoveryHeartbeatResponse::checkpointFingerprint,
                        DurableTestRecoveryHeartbeatResponse::idempotentReplay)
                .containsExactly("run-a", "RESUMING", "recovery-instance-a", 4L, 9L,
                        SHA_B, false);

        ArgumentCaptor<DurableTestExecutionCheckpointRepository.RecoveryHeartbeatCommand> command =
                ArgumentCaptor.forClass(
                        DurableTestExecutionCheckpointRepository.RecoveryHeartbeatCommand.class);
        verify(checkpoints).heartbeatRecoveryLeaseIdempotently(
                command.capture(), eq(auditMutation));
        assertThat(command.getValue().clientRequestId()).isEqualTo("heartbeat-1");
        assertThat(command.getValue().requestFingerprint()).startsWith("sha256:");
        assertThat(command.getValue().expectedDispatch()).isSameAs(source);
        assertThat(command.getValue().leaseDuration()).isEqualTo(Duration.ofMinutes(2));

        InOrder order = inOrder(checkpoints, securityEvents);
        order.verify(checkpoints).findRecoveryDispatch(any(), any(), any(), any(), any());
        order.verify(securityEvents).boundAppend(any());
        order.verify(checkpoints).heartbeatRecoveryLeaseIdempotently(any(), eq(auditMutation));
    }

    @Test
    void responseLossReplayReturnsTheOriginalFenceAndAddsAReplayAudit() {
        IntegrationRequestContext identity = identity();
        DurableTestRecoveryDispatch source = dispatch(identity, "org-a", "project-a", 4, 8, SHA_A);
        DurableTestExecutionCheckpoint renewed = checkpoint("org-a", "project-a", 4, 9, SHA_B);
        DurableTestExecutionCheckpointRepository.RecoveryHeartbeatResult replay =
                result(renewed, true);
        when(checkpoints.findRecoveryDispatch(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(source));
        when(securityEvents.boundAppend(any())).thenReturn(auditMutation);
        when(checkpoints.heartbeatRecoveryLeaseIdempotently(any(), eq(auditMutation)))
                .thenReturn(replay);
        when(securityEvents.append(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DurableTestRecoveryHeartbeatResponse response = service.heartbeat(
                "run-a", request("heartbeat-1", source), identity);

        assertThat(response.idempotentReplay()).isTrue();
        ArgumentCaptor<TestSecurityEvent> replayAudit =
                ArgumentCaptor.forClass(TestSecurityEvent.class);
        verify(securityEvents).append(replayAudit.capture());
        assertThat(replayAudit.getValue().reasonCode())
                .isEqualTo("RG.TEST.DURABLE_HEARTBEAT_IDEMPOTENT_REPLAY");
    }

    @Test
    void rejectsAValidDispatchWhenTheAuthenticatedPrincipalChanges() {
        DurableTestRecoveryDispatch source = dispatch(
                identity(), "org-a", "project-a", 4, 8, SHA_A);
        when(checkpoints.findRecoveryDispatch(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(source));
        when(securityEvents.append(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertProblem(() -> service.heartbeat(
                        "run-a", request("heartbeat-1", source), identity("other-worker")),
                403, "RG.TEST.DURABLE_RECOVERY_PRINCIPAL_MISMATCH", false);

        verify(securityEvents, never()).boundAppend(any());
        verify(checkpoints, never()).heartbeatRecoveryLeaseIdempotently(any(), any());
    }

    @Test
    void hidesDispatchExistenceAcrossOrganizationAndProjectScopes() {
        DurableTestRecoveryDispatch source = dispatch(
                identity(), "org-other", "project-other", 4, 8, SHA_A);
        when(checkpoints.findRecoveryDispatch(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(source));

        assertProblem(() -> service.heartbeat(
                        "run-a", request("heartbeat-1", source), identity()),
                404, "RG.TEST.DURABLE_RECOVERY_DISPATCH_NOT_FOUND", false);

        verify(securityEvents, never()).boundAppend(any());
        verify(checkpoints, never()).heartbeatRecoveryLeaseIdempotently(any(), any());
    }

    @Test
    void failClosedAuditBindingPreventsLeaseRenewal() {
        DurableTestRecoveryDispatch source = dispatch(
                identity(), "org-a", "project-a", 4, 8, SHA_A);
        when(checkpoints.findRecoveryDispatch(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(source));
        when(securityEvents.boundAppend(any())).thenThrow(new IllegalStateException("audit down"));

        assertProblem(() -> service.heartbeat(
                        "run-a", request("heartbeat-1", source), identity()),
                503, "RG.INTEGRATION.SECURITY_AUDIT_UNAVAILABLE", true);

        verify(checkpoints, never()).heartbeatRecoveryLeaseIdempotently(any(), any());
    }

    @Test
    void mapsStaleExpiredAndUnissuedDispatchesToStablePayloadFreeProblems() {
        DurableTestRecoveryDispatch source = dispatch(
                identity(), "org-a", "project-a", 4, 8, SHA_A);
        when(checkpoints.findRecoveryDispatch(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(source));
        when(securityEvents.boundAppend(any())).thenReturn(auditMutation);
        DurableTestRecoveryHeartbeatRequest request = request("heartbeat-1", source);

        when(checkpoints.heartbeatRecoveryLeaseIdempotently(any(), eq(auditMutation)))
                .thenThrow(conflict(DurableTestExecutionCheckpointConflictException.Reason.STALE_FENCE));
        assertProblem(() -> service.heartbeat("run-a", request, identity()),
                409, "RG.TEST.DURABLE_STALE_FENCE", true);

        when(checkpoints.heartbeatRecoveryLeaseIdempotently(any(), eq(auditMutation)))
                .thenThrow(conflict(DurableTestExecutionCheckpointConflictException.Reason.LEASE_EXPIRED));
        assertProblem(() -> service.heartbeat("run-a", request, identity()),
                409, "RG.TEST.DURABLE_LEASE_EXPIRED", true);

        when(checkpoints.heartbeatRecoveryLeaseIdempotently(any(), eq(auditMutation)))
                .thenThrow(conflict(
                        DurableTestExecutionCheckpointConflictException.Reason.UNRECOGNIZED_DISPATCH));
        assertProblem(() -> service.heartbeat("run-a", request, identity()),
                409, "RG.TEST.DURABLE_UNRECOGNIZED_DISPATCH", false);
    }

    @Test
    void rejectsProductionAndMalformedRequestsBeforeDispatchLookup() {
        DurableTestRecoveryHeartbeatRequest malformed = new DurableTestRecoveryHeartbeatRequest(
                "wrong.version", "", null, "not-a-fingerprint");

        assertProblem(() -> service.heartbeat("run-a", malformed, identity("worker-a", "production")),
                403, "RG.TEST.DURABLE_ENVIRONMENT_FORBIDDEN", false);
        assertProblem(() -> service.heartbeat("run-a", malformed, identity()),
                400, "RG.TEST.DURABLE_HEARTBEAT_REQUEST_INVALID", false);

        verify(checkpoints, never()).findRecoveryDispatch(any(), any(), any(), any(), any());
    }

    private DurableTestRecoveryDispatch dispatch(
            IntegrationRequestContext authorizedIdentity,
            String organizationId,
            String projectId,
            long leaseEpoch,
            long revision,
            String checkpointFingerprint) {
        DurableTestRecoveryAuthorization authorization = mock(
                DurableTestRecoveryAuthorization.class);
        lenient().when(authorization.principalFingerprint()).thenReturn(
                DurableTestRecoveryPrincipal.fingerprint(objectMapper, authorizedIdentity));
        DurableTestRecoveryDispatch dispatch = mock(DurableTestRecoveryDispatch.class);
        lenient().when(dispatch.scope()).thenReturn(new DurableTestExecutionCheckpoint.Scope(
                "tenant-a", organizationId, projectId, "test", "original-runner"));
        lenient().when(dispatch.runId()).thenReturn("run-a");
        lenient().when(dispatch.engineExecutionId()).thenReturn("engine-a");
        lenient().when(dispatch.ownerId()).thenReturn("recovery-instance-a");
        lenient().when(dispatch.leaseEpoch()).thenReturn(leaseEpoch);
        lenient().when(dispatch.revision()).thenReturn(revision);
        lenient().when(dispatch.leaseExpiresAt()).thenReturn(
                Instant.parse("2026-07-17T00:03:00Z"));
        lenient().when(dispatch.checkpointFingerprint()).thenReturn(checkpointFingerprint);
        lenient().when(dispatch.dispatchFingerprint()).thenReturn(SHA_B);
        lenient().when(dispatch.authorization()).thenReturn(authorization);
        return dispatch;
    }

    private static DurableTestExecutionCheckpoint checkpoint(
            String organizationId, String projectId, long leaseEpoch, long revision,
            String checkpointFingerprint) {
        DurableTestExecutionCheckpoint checkpoint = mock(DurableTestExecutionCheckpoint.class);
        lenient().when(checkpoint.scope()).thenReturn(new DurableTestExecutionCheckpoint.Scope(
                "tenant-a", organizationId, projectId, "test", "original-runner"));
        lenient().when(checkpoint.runId()).thenReturn("run-a");
        lenient().when(checkpoint.checkpointFingerprint()).thenReturn(checkpointFingerprint);
        lenient().when(checkpoint.lifecycle()).thenReturn(new DurableTestExecutionCheckpoint.Lifecycle(
                DurableTestExecutionCheckpoint.Status.RESUMING, "recovery-instance-a",
                leaseEpoch, revision, Instant.parse("2026-07-17T00:00:00Z"),
                Instant.parse("2026-07-17T00:02:00Z"),
                Instant.parse("2026-07-17T00:04:00Z")));
        return checkpoint;
    }

    private static DurableTestExecutionCheckpointRepository.RecoveryHeartbeatResult result(
            DurableTestExecutionCheckpoint checkpoint, boolean replay) {
        DurableTestExecutionCheckpointRepository.RecoveryHeartbeatResult result =
                mock(DurableTestExecutionCheckpointRepository.RecoveryHeartbeatResult.class);
        lenient().when(result.checkpoint()).thenReturn(checkpoint);
        lenient().when(result.idempotentReplay()).thenReturn(replay);
        return result;
    }

    private static DurableTestRecoveryHeartbeatRequest request(
            String clientRequestId, DurableTestRecoveryDispatch dispatch) {
        return new DurableTestRecoveryHeartbeatRequest("", clientRequestId,
                new DurableTestRecoveryHeartbeatRequest.Fence(
                        dispatch.ownerId(), dispatch.leaseEpoch(), dispatch.revision()),
                dispatch.checkpointFingerprint());
    }

    private static IntegrationRequestContext identity() {
        return identity("worker-a", "test");
    }

    private static IntegrationRequestContext identity(String actorId) {
        return identity(actorId, "test");
    }

    private static IntegrationRequestContext identity(String actorId, String environment) {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", environment, "sg", "WORKLOAD", actorId,
                "dispatcher-a", "TEST_EXECUTION", "correlation-a", Set.of("quality"),
                "CONFIDENTIAL", "grant-a");
    }

    private static DurableTestExecutionCheckpointConflictException conflict(
            DurableTestExecutionCheckpointConflictException.Reason reason) {
        return new DurableTestExecutionCheckpointConflictException(
                reason, "secret internal dispatch detail");
    }

    private static void assertProblem(
            Runnable action, int status, String code, boolean retryable) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(status);
                    assertThat(failure.problem().code()).isEqualTo(code);
                    assertThat(failure.problem().retryable()).isEqualTo(retryable);
                    assertThat(failure.problem().details())
                            .doesNotContainValue("secret internal dispatch detail");
                });
    }
}
