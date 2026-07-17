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

    @Test
    void authenticatesScopedMaintenanceReadsClaimsResolutionsAndHistory() throws Exception {
        DurableWorkerQuarantineService service = mock(DurableWorkerQuarantineService.class);
        when(service.quarantines(eq(true), eq(25), any())).thenReturn(
                new DurableWorkerQuarantinesResponse("", true, List.of()));
        when(service.history(eq(20), any())).thenReturn(
                new DurableWorkerQuarantineHistoryResponse("", List.of()));
        when(service.claim(any(), any())).thenReturn(new DurableWorkerQuarantineClaimResponse(
                "", "CLAIMED", new DurableWorkerQuarantineKey("run-a", SHA),
                "operator-a", "server-token", 1,
                Instant.parse("2026-07-17T12:00:00Z"), false));
        when(service.resolve(any(), any())).thenReturn(
                new DurableWorkerQuarantineResolutionResponse("", "RESOLVED",
                        new DurableWorkerQuarantineKey("run-a", SHA), "operator-a", "DISCARD",
                        "AUTHORIZED_RETRY", 2, Instant.parse("2026-07-17T11:00:00Z"),
                        SHA, false));
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
        mvc.perform(post("/api/testing/durable-state/worker-quarantines/claims")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_RUNTIME_MAINTENANCE")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(claimJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value("operator-a"))
                .andExpect(jsonPath("$.claimToken").value("server-token"));
        mvc.perform(post("/api/testing/durable-state/worker-quarantines/resolutions")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_RUNTIME_MAINTENANCE")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(resolutionJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("DISCARD"))
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
                  "action": "DISCARD",
                  "reasonCode": "AUTHORIZED_RETRY"
                }
                """.formatted(SHA);
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
