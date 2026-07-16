package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DurableTestOwnerClaimServiceTest {

    private static final String SHA_A = "sha256:" + "a".repeat(64);
    private static final String SHA_B = "sha256:" + "b".repeat(64);

    @Mock
    private DurableTestExecutionCheckpointRepository checkpoints;
    @Mock
    private DurableTestRecoveryAuthorizer authorizer;
    @Mock
    private TestSecurityEventRepository securityEvents;

    private ObjectMapper objectMapper;
    private DurableTestOwnerClaimService service;
    private TestRuntimeTransactionMutation auditMutation;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        auditMutation = jdbc -> { };
        service = new DurableTestOwnerClaimService(checkpoints, authorizer, securityEvents,
                objectMapper, "recovery-instance-a", Duration.ofMinutes(2));
    }

    @Test
    void claimsOnlyAfterExactScopeDependencyAuthorizationAndBoundAudit() {
        DurableTestExecutionCheckpoint current = checkpoint(
                DurableTestExecutionCheckpoint.SCHEMA_VERSION,
                "org-a", "project-a", "old-owner", 3, 7, SHA_A);
        DurableTestExecutionCheckpoint claimed = checkpoint(
                DurableTestExecutionCheckpoint.SCHEMA_VERSION,
                "org-a", "project-a", "recovery-instance-a", 4, 8, SHA_B);
        DurableTestOwnerClaimRequest request = request("request-1", current);
        when(checkpoints.find("tenant-a", "test", "run-a")).thenReturn(Optional.of(current));
        when(checkpoints.findLeaseClaimResult(eq("tenant-a"), eq("test"), eq("request-1"), any()))
                .thenReturn(Optional.empty());
        when(securityEvents.boundAppend(any())).thenReturn(auditMutation);
        when(checkpoints.claimExpiredLeaseIdempotently(any(), eq(auditMutation)))
                .thenReturn(new DurableTestExecutionCheckpointRepository.LeaseClaimResult(
                        claimed, false));

        DurableTestOwnerClaimResponse response = service.claim("run-a", request, identity());

        assertThat(response).extracting(DurableTestOwnerClaimResponse::runId,
                        DurableTestOwnerClaimResponse::status,
                        DurableTestOwnerClaimResponse::ownerId,
                        DurableTestOwnerClaimResponse::leaseEpoch,
                        DurableTestOwnerClaimResponse::revision,
                        DurableTestOwnerClaimResponse::checkpointFingerprint,
                        DurableTestOwnerClaimResponse::idempotentReplay)
                .containsExactly("run-a", "RESUMING", "recovery-instance-a", 4L, 8L,
                        SHA_B, false);
        assertThat(response.target()).isEqualTo(new DurableTestOwnerClaimResponse.Target(
                "GRAPH", "graph-a", SHA_A));

        ArgumentCaptor<DurableTestExecutionCheckpointRepository.ResumeLeaseCommand> command =
                ArgumentCaptor.forClass(
                        DurableTestExecutionCheckpointRepository.ResumeLeaseCommand.class);
        verify(checkpoints).claimExpiredLeaseIdempotently(command.capture(), eq(auditMutation));
        assertThat(command.getValue().clientRequestId()).isEqualTo("request-1");
        assertThat(command.getValue().requestFingerprint()).startsWith("sha256:");
        assertThat(command.getValue().claim().claimantOwnerId()).isEqualTo("recovery-instance-a");
        assertThat(command.getValue().claim().leaseDuration()).isEqualTo(Duration.ofMinutes(2));
        assertThat(command.getValue().claim().expectedFence())
                .isEqualTo(new DurableTestExecutionCheckpointRepository.Fence("old-owner", 3, 7));

        InOrder order = inOrder(authorizer, securityEvents, checkpoints);
        order.verify(authorizer).authorize(current, identity());
        order.verify(securityEvents).boundAppend(any());
        order.verify(checkpoints).claimExpiredLeaseIdempotently(any(), eq(auditMutation));
    }

    @Test
    void ambiguousRetryReturnsTheOriginalResultWithoutReauthorizingCurrentDependencies() {
        DurableTestExecutionCheckpoint current = checkpoint(
                DurableTestExecutionCheckpoint.SCHEMA_VERSION,
                "org-a", "project-a", "later-owner", 9, 12, SHA_B);
        DurableTestExecutionCheckpoint original = checkpoint(
                DurableTestExecutionCheckpoint.SCHEMA_VERSION,
                "org-a", "project-a", "former-instance", 4, 8, SHA_A);
        DurableTestOwnerClaimRequest request = new DurableTestOwnerClaimRequest("", "request-1",
                new DurableTestOwnerClaimRequest.Fence("old-owner", 3, 7), SHA_A);
        when(checkpoints.find("tenant-a", "test", "run-a")).thenReturn(Optional.of(current));
        when(checkpoints.findLeaseClaimResult(eq("tenant-a"), eq("test"), eq("request-1"), any()))
                .thenReturn(Optional.of(
                        new DurableTestExecutionCheckpointRepository.LeaseClaimResult(original, true)));
        when(securityEvents.append(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DurableTestOwnerClaimResponse response = service.claim("run-a", request, identity());

        assertThat(response.idempotentReplay()).isTrue();
        assertThat(response.ownerId()).isEqualTo("former-instance");
        assertThat(response.checkpointFingerprint()).isEqualTo(SHA_A);
        verify(authorizer, never()).authorize(any(), any());
        verify(checkpoints, never()).claimExpiredLeaseIdempotently(any(), any());
    }

    @Test
    void sameRequestRacingAcrossInstancesRecoversTheWinningImmutableResult() {
        DurableTestExecutionCheckpoint current = checkpoint(
                DurableTestExecutionCheckpoint.SCHEMA_VERSION,
                "org-a", "project-a", "old-owner", 3, 7, SHA_A);
        DurableTestExecutionCheckpoint winner = checkpoint(
                DurableTestExecutionCheckpoint.SCHEMA_VERSION,
                "org-a", "project-a", "other-instance", 4, 8, SHA_B);
        when(checkpoints.find("tenant-a", "test", "run-a")).thenReturn(Optional.of(current));
        when(checkpoints.findLeaseClaimResult(eq("tenant-a"), eq("test"), eq("request-1"), any()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(
                        new DurableTestExecutionCheckpointRepository.LeaseClaimResult(winner, true)));
        when(securityEvents.boundAppend(any())).thenReturn(auditMutation);
        when(checkpoints.claimExpiredLeaseIdempotently(any(), eq(auditMutation)))
                .thenThrow(new DurableTestExecutionCheckpointConflictException(
                        DurableTestExecutionCheckpointConflictException.Reason.IDEMPOTENCY_CONFLICT,
                        "another instance committed"));

        DurableTestOwnerClaimResponse response = service.claim(
                "run-a", request("request-1", current), identity());

        assertThat(response.idempotentReplay()).isTrue();
        assertThat(response.ownerId()).isEqualTo("other-instance");
    }

    @Test
    void repositoryLevelRaceReplayIsIndependentlyAuditedBeforeReturning() {
        DurableTestExecutionCheckpoint current = checkpoint(
                DurableTestExecutionCheckpoint.SCHEMA_VERSION,
                "org-a", "project-a", "old-owner", 3, 7, SHA_A);
        DurableTestExecutionCheckpoint winner = checkpoint(
                DurableTestExecutionCheckpoint.SCHEMA_VERSION,
                "org-a", "project-a", "other-instance", 4, 8, SHA_B);
        when(checkpoints.find("tenant-a", "test", "run-a")).thenReturn(Optional.of(current));
        when(checkpoints.findLeaseClaimResult(eq("tenant-a"), eq("test"), eq("request-1"), any()))
                .thenReturn(Optional.empty());
        when(securityEvents.boundAppend(any())).thenReturn(auditMutation);
        when(checkpoints.claimExpiredLeaseIdempotently(any(), eq(auditMutation)))
                .thenReturn(new DurableTestExecutionCheckpointRepository.LeaseClaimResult(
                        winner, true));
        when(securityEvents.append(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DurableTestOwnerClaimResponse response = service.claim(
                "run-a", request("request-1", current), identity());

        assertThat(response.idempotentReplay()).isTrue();
        ArgumentCaptor<TestSecurityEvent> replayAudit =
                ArgumentCaptor.forClass(TestSecurityEvent.class);
        verify(securityEvents).append(replayAudit.capture());
        assertThat(replayAudit.getValue().reasonCode())
                .isEqualTo("RG.TEST.DURABLE_OWNER_CLAIM_IDEMPOTENT_REPLAY");
    }

    @Test
    void hidesRunExistenceAcrossOrganizationAndProjectScopes() {
        DurableTestExecutionCheckpoint current = checkpoint(
                DurableTestExecutionCheckpoint.SCHEMA_VERSION,
                "org-other", "project-other", "old-owner", 3, 7, SHA_A);
        when(checkpoints.find("tenant-a", "test", "run-a")).thenReturn(Optional.of(current));

        assertProblem(() -> service.claim("run-a", request("request-1", current), identity()),
                404, "RG.TEST.DURABLE_EXECUTION_NOT_FOUND", false);

        verify(checkpoints, never()).findLeaseClaimResult(any(), any(), any(), any());
        verify(authorizer, never()).authorize(any(), any());
    }

    @Test
    void rejectsLegacyCheckpointBeforeDependencyAuthorizationOrLeaseMutation() {
        DurableTestExecutionCheckpoint current = checkpoint(
                DurableTestExecutionCheckpoint.SCHEMA_VERSION_V1,
                "org-a", "project-a", "old-owner", 3, 7, SHA_A);
        when(checkpoints.find("tenant-a", "test", "run-a")).thenReturn(Optional.of(current));
        when(checkpoints.findLeaseClaimResult(eq("tenant-a"), eq("test"), eq("request-1"), any()))
                .thenReturn(Optional.empty());
        when(securityEvents.append(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertProblem(() -> service.claim("run-a", request("request-1", current), identity()),
                409, "RG.TEST.DURABLE_CHECKPOINT_MIGRATION_REQUIRED", false);

        verify(authorizer, never()).authorize(any(), any());
        verify(checkpoints, never()).claimExpiredLeaseIdempotently(any(), any());
    }

    @Test
    void failClosedAuditBindingPreventsTheFirstLeaseMutation() {
        DurableTestExecutionCheckpoint current = checkpoint(
                DurableTestExecutionCheckpoint.SCHEMA_VERSION,
                "org-a", "project-a", "old-owner", 3, 7, SHA_A);
        when(checkpoints.find("tenant-a", "test", "run-a")).thenReturn(Optional.of(current));
        when(checkpoints.findLeaseClaimResult(eq("tenant-a"), eq("test"), eq("request-1"), any()))
                .thenReturn(Optional.empty());
        when(securityEvents.boundAppend(any())).thenThrow(new IllegalStateException("audit down"));

        assertProblem(() -> service.claim("run-a", request("request-1", current), identity()),
                503, "RG.INTEGRATION.SECURITY_AUDIT_UNAVAILABLE", true);

        verify(checkpoints, never()).claimExpiredLeaseIdempotently(any(), any());
    }

    @Test
    void mapsLeaseStateConflictsWithoutReturningCheckpointMaterial() {
        DurableTestExecutionCheckpoint current = checkpoint(
                DurableTestExecutionCheckpoint.SCHEMA_VERSION,
                "org-a", "project-a", "old-owner", 3, 7, SHA_A);
        when(checkpoints.find("tenant-a", "test", "run-a")).thenReturn(Optional.of(current));
        when(checkpoints.findLeaseClaimResult(eq("tenant-a"), eq("test"), eq("request-1"), any()))
                .thenReturn(Optional.empty());
        when(securityEvents.boundAppend(any())).thenReturn(auditMutation);
        when(checkpoints.claimExpiredLeaseIdempotently(any(), eq(auditMutation)))
                .thenThrow(new DurableTestExecutionCheckpointConflictException(
                        DurableTestExecutionCheckpointConflictException.Reason.LEASE_ACTIVE,
                        "secret internal checkpoint detail"));

        assertProblem(() -> service.claim("run-a", request("request-1", current), identity()),
                409, "RG.TEST.DURABLE_LEASE_ACTIVE", true);
    }

    @Test
    void rejectsProductionAndMalformedCallerCommandsBeforeStoreAccess() {
        DurableTestOwnerClaimRequest malformed = new DurableTestOwnerClaimRequest(
                "wrong.version", "", null, "not-a-fingerprint");

        assertProblem(() -> service.claim("run-a", malformed, identity("production")),
                403, "RG.TEST.DURABLE_ENVIRONMENT_FORBIDDEN", false);
        assertProblem(() -> service.claim("run-a", malformed, identity()),
                400, "RG.TEST.DURABLE_OWNER_CLAIM_REQUEST_INVALID", false);

        verify(checkpoints, never()).find(any(), any(), any());
    }

    private static DurableTestOwnerClaimRequest request(
            String clientRequestId, DurableTestExecutionCheckpoint checkpoint) {
        return new DurableTestOwnerClaimRequest("", clientRequestId,
                new DurableTestOwnerClaimRequest.Fence(
                        checkpoint.lifecycle().ownerId(), checkpoint.lifecycle().leaseEpoch(),
                        checkpoint.lifecycle().revision()), checkpoint.checkpointFingerprint());
    }

    private static DurableTestExecutionCheckpoint checkpoint(
            String schemaVersion, String organizationId, String projectId, String ownerId,
            long leaseEpoch, long revision, String checkpointFingerprint) {
        DurableTestExecutionCheckpoint checkpoint =
                org.mockito.Mockito.mock(DurableTestExecutionCheckpoint.class);
        DurableTestExecutionCheckpoint.ControlDependencies dependencies =
                org.mockito.Mockito.mock(DurableTestExecutionCheckpoint.ControlDependencies.class);
        lenient().when(checkpoint.schemaVersion()).thenReturn(schemaVersion);
        lenient().when(checkpoint.scope()).thenReturn(new DurableTestExecutionCheckpoint.Scope(
                "tenant-a", organizationId, projectId, "test", "original-runner"));
        lenient().when(checkpoint.runId()).thenReturn("run-a");
        lenient().when(checkpoint.checkpointFingerprint()).thenReturn(checkpointFingerprint);
        lenient().when(checkpoint.lifecycle()).thenReturn(new DurableTestExecutionCheckpoint.Lifecycle(
                DurableTestExecutionCheckpoint.Status.RESUMING, ownerId, leaseEpoch, revision,
                Instant.parse("2026-07-16T00:00:00Z"),
                Instant.parse("2026-07-16T00:01:00Z"),
                Instant.parse("2026-07-16T00:03:00Z")));
        lenient().when(checkpoint.dependencies()).thenReturn(dependencies);
        lenient().when(dependencies.target()).thenReturn(new DurableTestExecutionCheckpoint.ExecutionTargetRef(
                "GRAPH", "graph-a", SHA_A));
        return checkpoint;
    }

    private static IntegrationRequestContext identity() {
        return identity("test");
    }

    private static IntegrationRequestContext identity(String environment) {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", environment,
                "sg", "WORKLOAD", "recovery-worker", "", "TEST_EXECUTION", "correlation-a",
                Set.of("test-operators"), "CONFIDENTIAL", "");
    }

    private static void assertProblem(Runnable action, int status, String code, boolean retryable) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(status);
                    assertThat(failure.problem().code()).isEqualTo(code);
                    assertThat(failure.problem().retryable()).isEqualTo(retryable);
                    assertThat(failure.problem().details()).doesNotContainValue(
                            "secret internal checkpoint detail");
                });
    }
}
