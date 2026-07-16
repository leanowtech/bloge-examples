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

class DurableStateProjectionFindingControllerTest {

    @Test
    void authenticatesDedicatedMaintenanceReadsAndCommands() throws Exception {
        DurableStateProjectionFindingService service = mock(DurableStateProjectionFindingService.class);
        when(service.findings(eq(true), eq(25), any())).thenReturn(
                new DurableStateProjectionFindingsResponse("", true, List.of()));
        when(service.claim(any(), any())).thenReturn(
                new DurableStateProjectionFindingClaimResponse("", "CLAIMED",
                        new DurableStateProjectionFindingKey("EXECUTION", "execution-a"),
                        "operator-a", "server-token", 4,
                        Instant.parse("2026-07-17T06:00:00Z"), false));
        when(service.resolve(any(), any())).thenReturn(
                new DurableStateProjectionFindingResolutionResponse("", "RESOLVED",
                        new DurableStateProjectionFindingKey("EXECUTION", "execution-a"),
                        "operator-a", "QUARANTINED", 5,
                        Instant.parse("2026-07-17T05:30:00Z"), false));
        MockMvc mvc = mvc(service, Set.of("TEST_RUNTIME_MAINTENANCE"));

        mvc.perform(get("/api/testing/durable-state/projection-findings")
                        .queryParam("actionableOnly", "true").queryParam("limit", "25")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_RUNTIME_MAINTENANCE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion")
                        .value(DurableStateProjectionFindingsResponse.SCHEMA_VERSION))
                .andExpect(jsonPath("$.actionableOnly").value(true));
        mvc.perform(post("/api/testing/durable-state/projection-findings/claims")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_RUNTIME_MAINTENANCE")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(claimJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value("operator-a"))
                .andExpect(jsonPath("$.claimToken").value("server-token"));
        mvc.perform(post("/api/testing/durable-state/projection-findings/resolutions")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_RUNTIME_MAINTENANCE")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(resolutionJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolution").value("QUARANTINED"))
                .andExpect(jsonPath("$.claimToken").doesNotExist());

        verify(service).findings(eq(true), eq(25),
                org.mockito.ArgumentMatchers.argThat(identity ->
                        identity.actorId().equals("operator-a")
                                && identity.purpose().equals("TEST_RUNTIME_MAINTENANCE")));
    }

    @Test
    void rejectsWrongPurposeAndCallerSelectedOwnerBeforeServiceEntry() throws Exception {
        DurableStateProjectionFindingService service = mock(DurableStateProjectionFindingService.class);
        MockMvc mvc = mvc(service, Set.of("TEST_RUNTIME_MAINTENANCE"));
        String base = claimJson().stripTrailing();
        String withOwner = base.substring(0, base.lastIndexOf('}'))
                + ",\n  \"claimOwner\": \"caller-selected\"\n}";

        mvc.perform(post("/api/testing/durable-state/projection-findings/claims")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(claimJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.PURPOSE_FORBIDDEN"));
        mvc.perform(post("/api/testing/durable-state/projection-findings/claims")
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
                  "schemaVersion": "bloge.durableStateProjectionFindingClaimRequest.v1",
                  "clientRequestId": "claim-1",
                  "key": {"entityType": "EXECUTION", "rowId": "execution-a"},
                  "claimDurationSeconds": 120
                }
                """;
    }

    private static String resolutionJson() {
        return """
                {
                  "schemaVersion": "bloge.durableStateProjectionFindingResolutionRequest.v1",
                  "clientRequestId": "resolve-1",
                  "key": {"entityType": "EXECUTION", "rowId": "execution-a"},
                  "claimToken": "server-token",
                  "claimVersion": 4,
                  "claimUntil": "2026-07-17T06:00:00Z",
                  "resolution": "QUARANTINED"
                }
                """;
    }

    private static MockMvc mvc(
            DurableStateProjectionFindingService service,
            Set<String> purposes) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "test-runtime-operator", "control-plane", "org-a", "global-ops", "test", "sg",
                "WORKLOAD", "operator-a", "", purposes, Instant.MAX, true,
                Set.of("resource-gateway-test-runtime-operators"), "RESTRICTED", "", Instant.MAX);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("test-token", identity, false),
                new RecordingAudit());
        return MockMvcBuilders.standaloneSetup(
                        new DurableStateProjectionFindingController(service, authenticator))
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
