package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableStateProjectionControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DurableStateProjectionReconciler;
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

class DurableStateProjectionFindingServiceTest {

    private DatabaseDurableStateProjectionControlPlane controlPlane;
    private TestSecurityEventRepository securityEvents;
    private DurableStateProjectionFindingService service;

    @BeforeEach
    void setUp() {
        controlPlane = mock(DatabaseDurableStateProjectionControlPlane.class);
        securityEvents = mock(TestSecurityEventRepository.class);
        when(securityEvents.append(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(securityEvents.boundAppend(any())).thenReturn(TestRuntimeTransactionMutation.noop());
        service = new DurableStateProjectionFindingService(controlPlane, securityEvents,
                new ObjectMapper().findAndRegisterModules(),
                "resource-gateway-test-runtime-operators", "RESTRICTED");
    }

    @Test
    void listsPayloadFreeFindingsOnlyForTheGlobalMaintenanceRole() {
        when(controlPlane.actionableFindings(25)).thenReturn(List.of(finding()));

        DurableStateProjectionFindingsResponse response =
                service.findings(true, 25, identity("test", "TEST_RUNTIME_MAINTENANCE",
                        Set.of("resource-gateway-test-runtime-operators"), "RESTRICTED"));

        assertThat(response.findings()).singleElement().satisfies(item -> {
            assertThat(item.key().rowId()).isEqualTo("execution-a");
            assertThat(item.columns()).containsExactly("execution_status");
            assertThat(item.toString()).doesNotContain("claim-token", "payload", "secret");
        });
        ArgumentCaptor<TestSecurityEvent> event = ArgumentCaptor.forClass(TestSecurityEvent.class);
        verify(securityEvents).append(event.capture());
        assertThat(event.getValue().reasonCode())
                .isEqualTo("RG.TEST.PROJECTION_FINDING_READ_ALLOWED");
    }

    @Test
    void rejectsEnvironmentPurposeGroupAndClearanceWithoutTouchingTheQueue() {
        assertForbidden(identity("production", "TEST_RUNTIME_MAINTENANCE",
                Set.of("resource-gateway-test-runtime-operators"), "RESTRICTED"),
                "RG.TEST.PROJECTION_FINDING_ENVIRONMENT_FORBIDDEN");
        assertForbidden(identity("test", "TEST_EXECUTION",
                Set.of("resource-gateway-test-runtime-operators"), "RESTRICTED"),
                "RG.TEST.PROJECTION_FINDING_PURPOSE_FORBIDDEN");
        assertForbidden(identity("test", "TEST_RUNTIME_MAINTENANCE",
                Set.of("tenant-operator"), "RESTRICTED"),
                "RG.TEST.PROJECTION_FINDING_GLOBAL_ROLE_REQUIRED");
        assertForbidden(identity("test", "TEST_RUNTIME_MAINTENANCE",
                Set.of("resource-gateway-test-runtime-operators"), "CONFIDENTIAL"),
                "RG.TEST.PROJECTION_FINDING_CLEARANCE_REQUIRED");

        verify(controlPlane, never()).findings(any(Integer.class));
        verify(controlPlane, never()).actionableFindings(any(Integer.class));
        ArgumentCaptor<TestSecurityEvent> events = ArgumentCaptor.forClass(TestSecurityEvent.class);
        verify(securityEvents, org.mockito.Mockito.times(4)).append(events.capture());
        assertThat(events.getAllValues()).allSatisfy(event ->
                assertThat(event.outcome()).isEqualTo("REJECTED"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void derivesClaimOwnerFromIdentityAndBindsATokenFreeActionAudit() {
        DatabaseDurableStateProjectionControlPlane.FindingClaim claim =
                new DatabaseDurableStateProjectionControlPlane.FindingClaim(key(), "operator-a",
                        "claim-token-must-not-enter-audit", 4,
                        Instant.parse("2026-07-17T06:00:00Z"));
        when(controlPlane.claimFinding(eq(key()), eq("operator-a"), eq("claim-1"),
                eq(java.time.Duration.ofSeconds(120)), any())).thenAnswer(invocation -> {
                    Function<DatabaseDurableStateProjectionControlPlane.FindingClaim,
                            TestRuntimeTransactionMutation> audit = invocation.getArgument(4);
                    assertThat(audit.apply(claim)).isNotNull();
                    return new DatabaseDurableStateProjectionControlPlane.FindingClaimResult(
                            DatabaseDurableStateProjectionControlPlane.ClaimDisposition.CLAIMED,
                            claim);
                });

        DurableStateProjectionFindingClaimResponse response = service.claim(
                new DurableStateProjectionFindingClaimRequest("", "claim-1",
                        new DurableStateProjectionFindingKey("EXECUTION", "execution-a"), 120),
                identity("test", "TEST_RUNTIME_MAINTENANCE",
                        Set.of("resource-gateway-test-runtime-operators"), "RESTRICTED"));

        assertThat(response.ownerId()).isEqualTo("operator-a");
        assertThat(response.claimToken()).isEqualTo("claim-token-must-not-enter-audit");
        ArgumentCaptor<TestSecurityEvent> event = ArgumentCaptor.forClass(TestSecurityEvent.class);
        verify(securityEvents).boundAppend(event.capture());
        assertThat(event.getValue().toString())
                .doesNotContain("claim-token-must-not-enter-audit");
        assertThat(event.getValue().facts()).containsEntry("findingVersion", 4L);
    }

    @Test
    void rejectsIdempotencyDriftAndAuditsTheAttempt() {
        when(controlPlane.claimFinding(eq(key()), eq("operator-a"), eq("claim-1"),
                eq(java.time.Duration.ofSeconds(120)), any())).thenReturn(
                new DatabaseDurableStateProjectionControlPlane.FindingClaimResult(
                        DatabaseDurableStateProjectionControlPlane.ClaimDisposition
                                .IDEMPOTENCY_CONFLICT,
                        null));

        assertThatThrownBy(() -> service.claim(
                new DurableStateProjectionFindingClaimRequest("", "claim-1",
                        new DurableStateProjectionFindingKey("EXECUTION", "execution-a"), 120),
                identity("test", "TEST_RUNTIME_MAINTENANCE",
                        Set.of("resource-gateway-test-runtime-operators"), "RESTRICTED")))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(409);
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.TEST.PROJECTION_FINDING_IDEMPOTENCY_CONFLICT");
                });

        ArgumentCaptor<TestSecurityEvent> event = ArgumentCaptor.forClass(TestSecurityEvent.class);
        verify(securityEvents).append(event.capture());
        assertThat(event.getValue().outcome()).isEqualTo("REJECTED");
    }

    @Test
    void resolvesOnlyTheExactServerIssuedFenceAndNeverAuditsItsToken() {
        Instant claimUntil = Instant.parse("2026-07-17T06:00:00Z");
        DatabaseDurableStateProjectionControlPlane.FindingResolution resolution =
                new DatabaseDurableStateProjectionControlPlane.FindingResolution(
                        key(), "operator-a",
                        DatabaseDurableStateProjectionControlPlane.Resolution.QUARANTINED,
                        5, Instant.parse("2026-07-17T05:30:00Z"));
        when(controlPlane.resolveFinding(any(), eq("resolve-1"),
                eq(DatabaseDurableStateProjectionControlPlane.Resolution.QUARANTINED), any()))
                .thenAnswer(invocation -> {
                    DatabaseDurableStateProjectionControlPlane.FindingClaim claim =
                            invocation.getArgument(0);
                    assertThat(claim.ownerId()).isEqualTo("operator-a");
                    assertThat(claim.claimToken()).isEqualTo("sensitive-token");
                    assertThat(claim.claimUntil()).isEqualTo(claimUntil);
                    Function<DatabaseDurableStateProjectionControlPlane.FindingResolution,
                            TestRuntimeTransactionMutation> audit = invocation.getArgument(3);
                    assertThat(audit.apply(resolution)).isNotNull();
                    return new DatabaseDurableStateProjectionControlPlane.FindingResolutionResult(
                            DatabaseDurableStateProjectionControlPlane.ResolutionDisposition.RESOLVED,
                            resolution);
                });

        DurableStateProjectionFindingResolutionResponse response = service.resolve(
                new DurableStateProjectionFindingResolutionRequest("", "resolve-1",
                        new DurableStateProjectionFindingKey("EXECUTION", "execution-a"),
                        "sensitive-token", 4, claimUntil, "QUARANTINED"),
                identity("test", "TEST_RUNTIME_MAINTENANCE",
                        Set.of("resource-gateway-test-runtime-operators"), "RESTRICTED"));

        assertThat(response.resolution()).isEqualTo("QUARANTINED");
        assertThat(response.toString()).doesNotContain("sensitive-token");
        ArgumentCaptor<TestSecurityEvent> event = ArgumentCaptor.forClass(TestSecurityEvent.class);
        verify(securityEvents).boundAppend(event.capture());
        assertThat(event.getValue().toString()).doesNotContain("sensitive-token");
    }

    private void assertForbidden(IntegrationRequestContext identity, String code) {
        assertThatThrownBy(() -> service.findings(false, 10, identity))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(403);
                    assertThat(failure.problem().code()).isEqualTo(code);
                });
    }

    private static DatabaseDurableStateProjectionControlPlane.FindingRecord finding() {
        return new DatabaseDurableStateProjectionControlPlane.FindingRecord(
                key(), DurableStateProjectionReconciler.FindingKind.PROJECTION_DRIFT,
                List.of("execution_status"), true,
                DurableStateProjectionReconciler.Outcome.DETECTED,
                DatabaseDurableStateProjectionControlPlane.FindingStatus.OPEN, 2,
                Instant.parse("2026-07-17T05:00:00Z"),
                Instant.parse("2026-07-17T05:15:00Z"),
                DatabaseDurableStateProjectionControlPlane.Resolution.NONE, null, "",
                Instant.EPOCH, 3);
    }

    private static DurableStateProjectionReconciler.EntityKey key() {
        return new DurableStateProjectionReconciler.EntityKey(
                DurableStateProjectionReconciler.EntityType.EXECUTION, "execution-a");
    }

    private static IntegrationRequestContext identity(
            String environment, String purpose, Set<String> groups, String clearance) {
        return new IntegrationRequestContext("control-plane", "org-a", "global-ops",
                environment, "sg", "WORKLOAD", "operator-a", "", purpose,
                "correlation-a", groups, clearance, "");
    }
}
