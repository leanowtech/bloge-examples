package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRecord;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationWorkloadIdentity;
import com.leanowtech.bloge.gateway.integration.StaticBearerIntegrationIdentityResolver;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DurableWorkerQuarantineControllerTest {

    private static final String SHA = "sha256:" + "a".repeat(64);
    private static final String APPROVAL_ID = "11111111-1111-1111-1111-111111111111";

    @Test
    void authenticatesScopedMaintenanceAndTwoPersonDiscardEndpoints() throws Exception {
        DurableWorkerQuarantineService service = mock(DurableWorkerQuarantineService.class);
        when(service.quarantines(eq(true), eq(25), any())).thenReturn(
                new DurableWorkerQuarantinesResponse("", true, List.of()));
        when(service.history(eq(20), any())).thenReturn(
                new DurableWorkerQuarantineHistoryResponse("", List.of()));
        when(service.discardHistory(eq(20), any())).thenReturn(
                new DurableWorkerQuarantineApprovedDiscardHistoryResponse("", List.of()));
        when(service.claim(any(), any())).thenReturn(new DurableWorkerQuarantineClaimResponse(
                "", "CLAIMED", new DurableWorkerQuarantineKey("run-a", SHA),
                "operator-a", "server-token", 1,
                Instant.parse("2026-07-17T12:00:00Z"), false));
        when(service.resolve(any(), any())).thenReturn(
                new DurableWorkerQuarantineResolutionResponse("", "RESOLVED",
                        new DurableWorkerQuarantineKey("run-a", SHA), "operator-a", "RELEASE",
                        "DEPENDENCY_FIXED", 2, Instant.parse("2026-07-17T11:00:00Z"),
                        SHA, false));
        when(service.approveDiscard(any(), any())).thenReturn(
                new DurableWorkerQuarantineDiscardApprovalResponse("", "APPROVED",
                        APPROVAL_ID, new DurableWorkerQuarantineKey("run-a", SHA),
                        "operator-a", 1, Instant.parse("2026-07-17T12:00:00Z"),
                        "checker-a", "AUTHORIZED_RETRY",
                        Instant.parse("2026-07-17T11:00:00Z"),
                        Instant.parse("2026-07-17T11:05:00Z"), SHA, false));
        when(service.discard(any(), any())).thenReturn(
                new DurableWorkerQuarantineApprovedDiscardResponse("", "DISCARDED",
                        new DurableWorkerQuarantineKey("run-a", SHA), "operator-a",
                        APPROVAL_ID, "checker-a", SHA, "AUTHORIZED_RETRY", 2,
                        Instant.parse("2026-07-17T11:00:00Z"), SHA, false));
        MockMvc mvc = mvc(service, Set.of("TEST_RUNTIME_MAINTENANCE"));

        mvc.perform(get("/api/testing/durable-state/worker-quarantines")
                        .queryParam("actionableOnly", "true").queryParam("limit", "25")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_RUNTIME_MAINTENANCE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion")
                        .value(DurableWorkerQuarantinesResponse.SCHEMA_VERSION));
        mvc.perform(get("/api/testing/durable-state/worker-quarantines/history")
                        .queryParam("limit", "20")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_RUNTIME_MAINTENANCE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion")
                        .value(DurableWorkerQuarantineHistoryResponse.SCHEMA_VERSION));
        mvc.perform(get("/api/testing/durable-state/worker-quarantines/approved-discards/history")
                        .queryParam("limit", "20")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_RUNTIME_MAINTENANCE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(
                        DurableWorkerQuarantineApprovedDiscardHistoryResponse.SCHEMA_VERSION));
        mvc.perform(post("/api/testing/durable-state/worker-quarantines/claims")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_RUNTIME_MAINTENANCE")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(claimJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value("operator-a"))
                .andExpect(jsonPath("$.claimToken").value("server-token"));
        mvc.perform(post("/api/testing/durable-state/worker-quarantines/discard-approvals")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_RUNTIME_MAINTENANCE")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(approvalJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalId").value(APPROVAL_ID))
                .andExpect(jsonPath("$.claimToken").doesNotExist());
        mvc.perform(post("/api/testing/durable-state/worker-quarantines/approved-discards")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_RUNTIME_MAINTENANCE")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(approvedDiscardJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value("operator-a"))
                .andExpect(jsonPath("$.approverId").value("checker-a"))
                .andExpect(jsonPath("$.claimToken").doesNotExist());
        mvc.perform(post("/api/testing/durable-state/worker-quarantines/resolutions")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_RUNTIME_MAINTENANCE")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(resolutionJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("RELEASE"))
                .andExpect(jsonPath("$.claimToken").doesNotExist());

        verify(service).quarantines(eq(true), eq(25),
                org.mockito.ArgumentMatchers.argThat(identity ->
                        identity.tenantId().equals("tenant-a")
                                && identity.projectId().equals("project-a")
                                && identity.actorId().equals("operator-a")));
    }

    @Test
    void rejectsWrongPurposeAndCallerSelectedOwnershipBeforeServiceEntry() throws Exception {
        DurableWorkerQuarantineService service = mock(DurableWorkerQuarantineService.class);
        MockMvc mvc = mvc(service, Set.of("TEST_RUNTIME_MAINTENANCE"));
        String withOwner = claimJson().replace("\n}",
                ",\n  \"claimOwner\": \"caller-selected\"\n}");

        mvc.perform(post("/api/testing/durable-state/worker-quarantines/claims")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(claimJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.PURPOSE_FORBIDDEN"));
        mvc.perform(post("/api/testing/durable-state/worker-quarantines/claims")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_RUNTIME_MAINTENANCE")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(withOwner))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RG.TEST.REQUEST_MALFORMED"));

        verifyNoInteractions(service);
    }

    private static String claimJson() {
        return """
                {
                  "schemaVersion": "bloge.durableWorkerQuarantineClaimRequest.v1",
                  "clientRequestId": "claim-1",
                  "key": {"runId": "run-a", "checkpointFingerprint": "%s"},
                  "claimDurationSeconds": 120
                }
                """.formatted(SHA);
    }

    private static String resolutionJson() {
        return """
                {
                  "schemaVersion": "bloge.durableWorkerQuarantineResolutionRequest.v1",
                  "clientRequestId": "resolve-1",
                  "key": {"runId": "run-a", "checkpointFingerprint": "%s"},
                  "claimToken": "server-token",
                  "claimVersion": 1,
                  "claimUntil": "2026-07-17T12:00:00Z",
                  "action": "RELEASE",
                  "reasonCode": "DEPENDENCY_FIXED"
                }
                """.formatted(SHA);
    }

    private static String approvalJson() {
        return """
                {
                  "schemaVersion": "bloge.durableWorkerQuarantineDiscardApprovalRequest.v1",
                  "clientRequestId": "approval-1",
                  "key": {"runId": "run-a", "checkpointFingerprint": "%s"},
                  "claimOwner": "operator-a",
                  "claimVersion": 1,
                  "claimUntil": "2026-07-17T12:00:00Z",
                  "reasonCode": "AUTHORIZED_RETRY",
                  "approvalDurationSeconds": 300
                }
                """.formatted(SHA);
    }

    private static String approvedDiscardJson() {
        return """
                {
                  "schemaVersion": "bloge.durableWorkerQuarantineApprovedDiscardRequest.v1",
                  "clientRequestId": "discard-1",
                  "key": {"runId": "run-a", "checkpointFingerprint": "%s"},
                  "claimToken": "server-token",
                  "claimVersion": 1,
                  "claimUntil": "2026-07-17T12:00:00Z",
                  "approvalId": "%s",
                  "reasonCode": "AUTHORIZED_RETRY"
                }
                """.formatted(SHA, APPROVAL_ID);
    }

    private static MockMvc mvc(
            DurableWorkerQuarantineService service, Set<String> purposes) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "test-runtime-operator", "tenant-a", "org-a", "project-a", "test", "sg",
                "WORKLOAD", "operator-a", "", purposes, Instant.MAX, true,
                Set.of("resource-gateway-test-runtime-operators"), "RESTRICTED", "", Instant.MAX);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("test-token", identity, false),
                new RecordingAudit());
        return MockMvcBuilders.standaloneSetup(
                        new DurableWorkerQuarantineController(service, authenticator))
                .setControllerAdvice(new TestExecutionProblemHandler()).build();
    }

    private static final class RecordingAudit implements IntegrationAccessAuditRepository {
        private final List<IntegrationAccessAuditRecord> records = new ArrayList<>();

        @Override
        public IntegrationAccessAuditRecord append(IntegrationAccessAuditRecord record) {
            IntegrationAccessAuditRecord stored = record.withSequence(records.size() + 1L);
            records.add(stored);
            return stored;
        }

        @Override
        public List<IntegrationAccessAuditRecord> recent(int limit) {
            return List.copyOf(records);
        }
    }
}
