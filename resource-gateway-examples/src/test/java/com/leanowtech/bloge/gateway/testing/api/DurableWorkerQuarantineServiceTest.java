package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableWorkerQuarantineControlPlane;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DurableWorkerQuarantineServiceTest {

    private DatabaseDurableWorkerQuarantineControlPlane controlPlane;
    private TestSecurityEventRepository securityEvents;
    private DurableWorkerQuarantineService service;

    @BeforeEach
    void setUp() {
        controlPlane = mock(DatabaseDurableWorkerQuarantineControlPlane.class);
        securityEvents = mock(TestSecurityEventRepository.class);
        when(securityEvents.append(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(securityEvents.boundAppend(any())).thenReturn(TestRuntimeTransactionMutation.noop());
        service = new DurableWorkerQuarantineService(controlPlane, securityEvents,
                new ObjectMapper().findAndRegisterModules(),
                "resource-gateway-test-runtime-operators",
                "resource-gateway-test-runtime-quarantine-approvers", "RESTRICTED");
    }

    @Test
    void listsOnlyTheVerifiedTenantProjectScopeForDedicatedMaintenanceOperators() {
        when(controlPlane.quarantines(scope(), true, 25)).thenReturn(List.of(record()));

        DurableWorkerQuarantinesResponse response =
                service.quarantines(true, 25, identity("test", "TEST_RUNTIME_MAINTENANCE",
                        Set.of("resource-gateway-test-runtime-operators"), "RESTRICTED"));

        assertThat(response.quarantines()).singleElement().satisfies(item -> {
            assertThat(item.key().runId()).isEqualTo("run-a");
            assertThat(item.state()).isEqualTo("AVAILABLE");
            assertThat(item.toString()).doesNotContain("token", "payload", "secret");
        });
        verify(controlPlane).quarantines(scope(), true, 25);
        ArgumentCaptor<TestSecurityEvent> event = ArgumentCaptor.forClass(TestSecurityEvent.class);
        verify(securityEvents).append(event.capture());
        assertThat(event.getValue().reasonCode())
                .isEqualTo("RG.TEST.WORKER_QUARANTINE_READ_ALLOWED");
    }

    @Test
    void readsTokenFreeHistoryOnlyFromTheVerifiedScope() {
        var history = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineHistoryRecord(
                "history-a", key(),
                DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason
                        .AUTHORIZATION_DENIED,
                32, 32, Instant.parse("2026-07-17T10:00:00Z"),
                Instant.parse("2026-07-17T10:30:00Z"),
                DatabaseDurableWorkerQuarantineControlPlane.ResolutionAction.DISCARD,
                "AUTHORIZED_RETRY", "operator-a", 2,
                Instant.parse("2026-07-17T11:00:00Z"), SHA, SHA);
        when(controlPlane.history(scope(), 25)).thenReturn(List.of(history));

        DurableWorkerQuarantineHistoryResponse response = service.history(25, authorized());

        assertThat(response.history()).singleElement().satisfies(item -> {
            assertThat(item.key().runId()).isEqualTo("run-a");
            assertThat(item.action()).isEqualTo("DISCARD");
            assertThat(item.toString()).doesNotContain("token", "payload", "secret");
        });
        verify(controlPlane).history(scope(), 25);
    }

    @Test
    void rejectsEnvironmentPurposeGroupAndClearanceBeforePersistenceAccess() {
        assertForbidden(identity("production", "TEST_RUNTIME_MAINTENANCE",
                        Set.of("resource-gateway-test-runtime-operators"), "RESTRICTED"),
                "RG.TEST.WORKER_QUARANTINE_ENVIRONMENT_FORBIDDEN");
        assertForbidden(identity("test", "TEST_EXECUTION",
                        Set.of("resource-gateway-test-runtime-operators"), "RESTRICTED"),
                "RG.TEST.WORKER_QUARANTINE_PURPOSE_FORBIDDEN");
        assertForbidden(identity("test", "TEST_RUNTIME_MAINTENANCE",
                        Set.of("tenant-operator"), "RESTRICTED"),
                "RG.TEST.WORKER_QUARANTINE_ROLE_REQUIRED");
        assertForbidden(identity("test", "TEST_RUNTIME_MAINTENANCE",
                        Set.of("resource-gateway-test-runtime-operators"), "CONFIDENTIAL"),
                "RG.TEST.WORKER_QUARANTINE_CLEARANCE_REQUIRED");

        verify(controlPlane, never()).quarantines(any(), any(Boolean.class), any(Integer.class));
    }

    @Test
    void derivesClaimOwnerFromIdentityAndNeverPlacesTheServerTokenInAudit() {
        var claim = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineClaim(
                key(), "operator-a", "sensitive-server-token", 1,
                Instant.parse("2026-07-17T12:00:00Z"));
        when(controlPlane.claim(eq(scope()), eq(key()), eq("operator-a"), eq("claim-1"),
                eq(java.time.Duration.ofSeconds(120)), any())).thenAnswer(invocation -> {
                    Function<DatabaseDurableWorkerQuarantineControlPlane.QuarantineClaim,
                            TestRuntimeTransactionMutation> audit = invocation.getArgument(5);
                    assertThat(audit.apply(claim)).isNotNull();
                    return new DatabaseDurableWorkerQuarantineControlPlane.QuarantineClaimResult(
                            DatabaseDurableWorkerQuarantineControlPlane.ClaimDisposition.CLAIMED,
                            claim);
                });

        DurableWorkerQuarantineClaimResponse response = service.claim(
                new DurableWorkerQuarantineClaimRequest("", "claim-1",
                        new DurableWorkerQuarantineKey("run-a", SHA), 120),
                authorized());

        assertThat(response.ownerId()).isEqualTo("operator-a");
        assertThat(response.claimToken()).isEqualTo("sensitive-server-token");
        ArgumentCaptor<TestSecurityEvent> event = ArgumentCaptor.forClass(TestSecurityEvent.class);
        verify(securityEvents).boundAppend(event.capture());
        assertThat(event.getValue().toString()).doesNotContain("sensitive-server-token");
    }

    @Test
    void releasesAnExactFenceToATokenFreeReceiptAndMapsFenceRejection() {
        Instant until = Instant.parse("2026-07-17T12:00:00Z");
        var receipt = new DatabaseDurableWorkerQuarantineControlPlane
                .QuarantineResolutionReceipt(key(), "operator-a",
                DatabaseDurableWorkerQuarantineControlPlane.ResolutionAction.RELEASE,
                "DEPENDENCY_FIXED", 2, Instant.parse("2026-07-17T11:00:00Z"), SHA);
        when(controlPlane.resolve(eq(scope()), any(), eq("resolve-1"),
                eq(DatabaseDurableWorkerQuarantineControlPlane.ResolutionAction.RELEASE),
                eq("DEPENDENCY_FIXED"), any())).thenAnswer(invocation -> {
                    var claim = (DatabaseDurableWorkerQuarantineControlPlane.QuarantineClaim)
                            invocation.getArgument(1);
                    assertThat(claim.ownerId()).isEqualTo("operator-a");
                    assertThat(claim.claimToken()).isEqualTo("sensitive-server-token");
                    Function<DatabaseDurableWorkerQuarantineControlPlane
                            .QuarantineResolutionReceipt, TestRuntimeTransactionMutation> audit =
                            invocation.getArgument(5);
                    assertThat(audit.apply(receipt)).isNotNull();
                    return new DatabaseDurableWorkerQuarantineControlPlane
                            .QuarantineResolutionResult(
                            DatabaseDurableWorkerQuarantineControlPlane
                                    .ResolutionDisposition.RESOLVED, receipt);
                });

        DurableWorkerQuarantineResolutionResponse response = service.resolve(
                new DurableWorkerQuarantineResolutionRequest("", "resolve-1",
                        new DurableWorkerQuarantineKey("run-a", SHA),
                        "sensitive-server-token", 1, until, "RELEASE", "DEPENDENCY_FIXED"),
                authorized());

        assertThat(response.action()).isEqualTo("RELEASE");
        assertThat(response.toString()).doesNotContain("sensitive-server-token");
        ArgumentCaptor<TestSecurityEvent> event = ArgumentCaptor.forClass(TestSecurityEvent.class);
        verify(securityEvents).boundAppend(event.capture());
        assertThat(event.getValue().toString()).doesNotContain("sensitive-server-token");

        when(controlPlane.resolve(eq(scope()), any(), eq("resolve-fenced"), any(), any(), any()))
                .thenReturn(new DatabaseDurableWorkerQuarantineControlPlane
                        .QuarantineResolutionResult(DatabaseDurableWorkerQuarantineControlPlane
                        .ResolutionDisposition.FENCE_REJECTED, null));
        assertThatThrownBy(() -> service.resolve(
                new DurableWorkerQuarantineResolutionRequest("", "resolve-fenced",
                        new DurableWorkerQuarantineKey("run-a", SHA),
                        "sensitive-server-token", 1, until, "RELEASE", "DEPENDENCY_FIXED"),
                authorized())).isInstanceOfSatisfying(
                IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(409);
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.TEST.WORKER_QUARANTINE_FENCE_REJECTED");
                });
    }

    @Test
    void rejectsInvalidResolutionReasonBeforePersistenceAccess() {
        assertThatThrownBy(() -> service.resolve(
                new DurableWorkerQuarantineResolutionRequest("", "resolve-invalid",
                        new DurableWorkerQuarantineKey("run-a", SHA),
                        "sensitive-server-token", 1,
                        Instant.parse("2026-07-17T12:00:00Z"),
                        "DISCARD", "contains spaces"), authorized()))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(400);
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.TEST.WORKER_QUARANTINE_REQUEST_INVALID");
                });

        verify(controlPlane, never()).resolve(any(), any(), any(), any(), any(), any());
    }

    @Test
    void legacyDirectDiscardMapsTheStableIndependentApprovalRequirement() {
        when(controlPlane.resolve(eq(scope()), any(), eq("legacy-discard"),
                eq(DatabaseDurableWorkerQuarantineControlPlane.ResolutionAction.DISCARD),
                eq("AUTHORIZED_RETRY"), any())).thenReturn(
                new DatabaseDurableWorkerQuarantineControlPlane.QuarantineResolutionResult(
                        DatabaseDurableWorkerQuarantineControlPlane.ResolutionDisposition
                                .APPROVAL_REQUIRED, null));

        assertThatThrownBy(() -> service.resolve(
                new DurableWorkerQuarantineResolutionRequest("", "legacy-discard",
                        new DurableWorkerQuarantineKey("run-a", SHA),
                        "sensitive-server-token", 1,
                        Instant.parse("2026-07-17T12:00:00Z"),
                        "DISCARD", "AUTHORIZED_RETRY"), authorized()))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(409);
                    assertThat(failure.problem().code()).isEqualTo(
                            "RG.TEST.WORKER_QUARANTINE_DISCARD_APPROVAL_REQUIRED");
                });
    }

    @Test
    void checkerApprovalRequiresTheApproverGroupAndNeverCarriesTheClaimToken() {
        Instant claimUntil = Instant.parse("2026-07-17T12:00:00Z");
        var approval = new DatabaseDurableWorkerQuarantineControlPlane.DiscardApproval(
                APPROVAL_ID, key(), "operator-a", 1, claimUntil, "checker-a",
                "AUTHORIZED_RETRY", Instant.parse("2026-07-17T11:00:00Z"),
                Instant.parse("2026-07-17T11:05:00Z"), SHA);
        when(controlPlane.approveDiscard(eq(scope()), eq(key()), eq("operator-a"), eq(1L),
                eq(claimUntil), eq("checker-a"), eq("approval-1"),
                eq("AUTHORIZED_RETRY"), eq(java.time.Duration.ofSeconds(300)), any()))
                .thenAnswer(invocation -> {
                    Function<DatabaseDurableWorkerQuarantineControlPlane.DiscardApproval,
                            TestRuntimeTransactionMutation> audit = invocation.getArgument(9);
                    assertThat(audit.apply(approval)).isNotNull();
                    return new DatabaseDurableWorkerQuarantineControlPlane.DiscardApprovalResult(
                            DatabaseDurableWorkerQuarantineControlPlane
                                    .DiscardApprovalDisposition.APPROVED, approval);
                });
        var request = new DurableWorkerQuarantineDiscardApprovalRequest("", "approval-1",
                new DurableWorkerQuarantineKey("run-a", SHA), "operator-a", 1,
                claimUntil, "AUTHORIZED_RETRY", 300);

        assertThatThrownBy(() -> service.approveDiscard(request, authorized()))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                        assertThat(failure.problem().code()).isEqualTo(
                                "RG.TEST.WORKER_QUARANTINE_APPROVER_ROLE_REQUIRED"));

        DurableWorkerQuarantineDiscardApprovalResponse response = service.approveDiscard(
                request, identity("test", "TEST_RUNTIME_MAINTENANCE",
                        Set.of("resource-gateway-test-runtime-quarantine-approvers"),
                        "RESTRICTED", "checker-a"));

        assertThat(response.claimOwner()).isEqualTo("operator-a");
        assertThat(response.approverId()).isEqualTo("checker-a");
        assertThat(response.toString()).doesNotContain("token", "secret");
        ArgumentCaptor<TestSecurityEvent> event = ArgumentCaptor.forClass(TestSecurityEvent.class);
        verify(securityEvents).boundAppend(event.capture());
        assertThat(event.getValue().reasonCode())
                .isEqualTo("RG.TEST.WORKER_QUARANTINE_DISCARD_APPROVAL_COMMITTED");
        assertThat(event.getValue().toString()).doesNotContain("token", "secret");
    }

    @Test
    void approvedDiscardDerivesMakerFromIdentityAndReturnsTwoPersonEvidence() {
        Instant claimUntil = Instant.parse("2026-07-17T12:00:00Z");
        var receipt = new DatabaseDurableWorkerQuarantineControlPlane.ApprovedDiscardReceipt(
                key(), "operator-a", APPROVAL_ID, "checker-a", SHA,
                "AUTHORIZED_RETRY", 2, Instant.parse("2026-07-17T11:10:00Z"), SHA);
        when(controlPlane.discard(eq(scope()), any(), eq(APPROVAL_ID), eq("discard-1"),
                eq("AUTHORIZED_RETRY"), any())).thenAnswer(invocation -> {
                    var claim = (DatabaseDurableWorkerQuarantineControlPlane.QuarantineClaim)
                            invocation.getArgument(1);
                    assertThat(claim.ownerId()).isEqualTo("operator-a");
                    assertThat(claim.claimToken()).isEqualTo("sensitive-server-token");
                    Function<DatabaseDurableWorkerQuarantineControlPlane.ApprovedDiscardReceipt,
                            TestRuntimeTransactionMutation> audit = invocation.getArgument(5);
                    assertThat(audit.apply(receipt)).isNotNull();
                    return new DatabaseDurableWorkerQuarantineControlPlane.ApprovedDiscardResult(
                            DatabaseDurableWorkerQuarantineControlPlane
                                    .ApprovedDiscardDisposition.DISCARDED, receipt);
                });

        DurableWorkerQuarantineApprovedDiscardResponse response = service.discard(
                new DurableWorkerQuarantineApprovedDiscardRequest("", "discard-1",
                        new DurableWorkerQuarantineKey("run-a", SHA),
                        "sensitive-server-token", 1, claimUntil, APPROVAL_ID,
                        "AUTHORIZED_RETRY"), authorized());

        assertThat(response.ownerId()).isEqualTo("operator-a");
        assertThat(response.approverId()).isEqualTo("checker-a");
        assertThat(response.approvalId()).isEqualTo(APPROVAL_ID);
        assertThat(response.toString()).doesNotContain("sensitive-server-token");
        ArgumentCaptor<TestSecurityEvent> event = ArgumentCaptor.forClass(TestSecurityEvent.class);
        verify(securityEvents).boundAppend(event.capture());
        assertThat(event.getValue().toString()).doesNotContain("sensitive-server-token");
    }

    @Test
    void mapsEveryRetainedRequestTombstoneToOneStableConflictCode() {
        Instant claimUntil = Instant.parse("2026-07-17T12:00:00Z");
        when(controlPlane.claim(eq(scope()), eq(key()), eq("operator-a"), eq("claim-expired"),
                eq(java.time.Duration.ofSeconds(120)), any())).thenReturn(
                new DatabaseDurableWorkerQuarantineControlPlane.QuarantineClaimResult(
                        DatabaseDurableWorkerQuarantineControlPlane.ClaimDisposition
                                .REPLAY_WINDOW_EXPIRED, null));
        when(controlPlane.resolve(eq(scope()), any(), eq("resolve-expired"), any(), any(), any()))
                .thenReturn(new DatabaseDurableWorkerQuarantineControlPlane
                        .QuarantineResolutionResult(DatabaseDurableWorkerQuarantineControlPlane
                        .ResolutionDisposition.REPLAY_WINDOW_EXPIRED, null));
        when(controlPlane.approveDiscard(eq(scope()), eq(key()), eq("operator-a"), eq(1L),
                eq(claimUntil), eq("checker-a"), eq("approval-expired"),
                eq("AUTHORIZED_RETRY"), eq(java.time.Duration.ofSeconds(300)), any()))
                .thenReturn(new DatabaseDurableWorkerQuarantineControlPlane
                        .DiscardApprovalResult(DatabaseDurableWorkerQuarantineControlPlane
                        .DiscardApprovalDisposition.REPLAY_WINDOW_EXPIRED, null));
        when(controlPlane.discard(eq(scope()), any(), eq(APPROVAL_ID), eq("discard-expired"),
                eq("AUTHORIZED_RETRY"), any())).thenReturn(
                new DatabaseDurableWorkerQuarantineControlPlane.ApprovedDiscardResult(
                        DatabaseDurableWorkerQuarantineControlPlane
                                .ApprovedDiscardDisposition.REPLAY_WINDOW_EXPIRED, null));

        assertReplayExpired(() -> service.claim(new DurableWorkerQuarantineClaimRequest("",
                "claim-expired", new DurableWorkerQuarantineKey("run-a", SHA), 120),
                authorized()));
        assertReplayExpired(() -> service.resolve(new DurableWorkerQuarantineResolutionRequest("",
                "resolve-expired", new DurableWorkerQuarantineKey("run-a", SHA),
                "sensitive-server-token", 1, claimUntil, "RELEASE", "AUTHORIZED_RETRY"),
                authorized()));
        assertReplayExpired(() -> service.approveDiscard(
                new DurableWorkerQuarantineDiscardApprovalRequest("", "approval-expired",
                        new DurableWorkerQuarantineKey("run-a", SHA), "operator-a", 1,
                        claimUntil, "AUTHORIZED_RETRY", 300),
                identity("test", "TEST_RUNTIME_MAINTENANCE",
                        Set.of("resource-gateway-test-runtime-quarantine-approvers"),
                        "RESTRICTED", "checker-a")));
        assertReplayExpired(() -> service.discard(
                new DurableWorkerQuarantineApprovedDiscardRequest("", "discard-expired",
                        new DurableWorkerQuarantineKey("run-a", SHA),
                        "sensitive-server-token", 1, claimUntil, APPROVAL_ID,
                        "AUTHORIZED_RETRY"), authorized()));
    }

    @Test
    void readsTokenFreeApprovedDiscardHistoryFromTheVerifiedScope() {
        var history = new DatabaseDurableWorkerQuarantineControlPlane
                .ApprovedDiscardHistoryRecord("history-discard", key(),
                DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason
                        .AUTHORIZATION_DENIED,
                32, 32, Instant.parse("2026-07-17T10:00:00Z"),
                Instant.parse("2026-07-17T10:30:00Z"), "AUTHORIZED_RETRY",
                "operator-a", APPROVAL_ID, "checker-a", SHA, 2,
                Instant.parse("2026-07-17T11:00:00Z"), SHA, SHA);
        when(controlPlane.discardHistory(scope(), 25)).thenReturn(List.of(history));

        var response = service.discardHistory(25, authorized());

        assertThat(response.history()).singleElement().satisfies(item -> {
            assertThat(item.ownerId()).isEqualTo("operator-a");
            assertThat(item.approverId()).isEqualTo("checker-a");
            assertThat(item.toString()).doesNotContain("token", "payload", "secret");
        });
        verify(controlPlane).discardHistory(scope(), 25);
    }

    private void assertForbidden(IntegrationRequestContext identity, String code) {
        assertThatThrownBy(() -> service.quarantines(false, 10, identity))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(403);
                    assertThat(failure.problem().code()).isEqualTo(code);
                });
    }

    private static void assertReplayExpired(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(
                IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(409);
                    assertThat(failure.problem().code()).isEqualTo(
                            "RG.TEST.WORKER_QUARANTINE_REPLAY_WINDOW_EXPIRED");
                });
    }

    private static final String SHA = "sha256:" + "a".repeat(64);
    private static final String APPROVAL_ID = "11111111-1111-1111-1111-111111111111";

    private static DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey key() {
        return new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey("run-a", SHA);
    }

    private static com.leanowtech.bloge.gateway.testing.api
            .DurableTestExecutionCheckpointRepository.WorkerAcquisitionScope scope() {
        return new DurableTestExecutionCheckpointRepository.WorkerAcquisitionScope(
                "tenant-a", "org-a", "project-a", "test");
    }

    private static DatabaseDurableWorkerQuarantineControlPlane.QuarantineRecord record() {
        return new DatabaseDurableWorkerQuarantineControlPlane.QuarantineRecord(
                "run-a", SHA,
                DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason
                        .AUTHORIZATION_DENIED,
                32, 32, Instant.parse("2026-07-17T10:00:00Z"),
                Instant.parse("2026-07-17T10:30:00Z"),
                DatabaseDurableWorkerQuarantineControlPlane.QuarantineState.AVAILABLE,
                "", Instant.EPOCH, 0);
    }

    private static IntegrationRequestContext authorized() {
        return identity("test", "TEST_RUNTIME_MAINTENANCE",
                Set.of("resource-gateway-test-runtime-operators"), "RESTRICTED");
    }

    private static IntegrationRequestContext identity(
            String environment, String purpose, Set<String> groups, String clearance) {
        return identity(environment, purpose, groups, clearance, "operator-a");
    }

    private static IntegrationRequestContext identity(
            String environment,
            String purpose,
            Set<String> groups,
            String clearance,
            String actorId) {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", environment,
                "sg", "WORKLOAD", actorId, "", purpose, "correlation-a",
                groups, clearance, "");
    }
}
