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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DurableTestWorkerAcquisitionControllerTest {

    @Test
    void authenticatesDedicatedOperationAndNeverReturnsHiddenDispatchOrPayload() throws Exception {
        DurableTestWorkerAcquisitionService service =
                mock(DurableTestWorkerAcquisitionService.class);
        when(service.acquire(any(), any())).thenReturn(
                new DurableTestWorkerAcquisitionResponse(
                        "", "ACQUIRED", Instant.parse("2026-07-17T00:00:00Z"),
                        new DurableTestWorkerAcquisitionResponse.Assignment(
                                "run-a", "RESUMING", "worker-a", 4, 8,
                                Instant.parse("2026-07-17T00:02:00Z"),
                                "sha256:" + "a".repeat(64),
                                new DurableTestWorkerAcquisitionResponse.Target(
                                        "GRAPH", "graph-a", "sha256:" + "b".repeat(64))),
                        false));
        MockMvc mvc = mvc(service, Set.of("TEST_EXECUTION"));

        mvc.perform(post("/api/testing/durable-executions/worker-acquisitions")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(requestJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion")
                        .value(DurableTestWorkerAcquisitionResponse.SCHEMA_VERSION))
                .andExpect(jsonPath("$.outcome").value("ACQUIRED"))
                .andExpect(jsonPath("$.assignment.runId").value("run-a"))
                .andExpect(jsonPath("$.assignment.target.id").value("graph-a"))
                .andExpect(jsonPath("$.dispatch").doesNotExist())
                .andExpect(jsonPath("$.assignment.dispatch").doesNotExist())
                .andExpect(jsonPath("$.fixture").doesNotExist())
                .andExpect(jsonPath("$.engineState").doesNotExist());

        verify(service).acquire(any(), org.mockito.ArgumentMatchers.argThat(identity ->
                identity.tenantId().equals("tenant-a")
                        && identity.projectId().equals("project-a")
                        && identity.purpose().equals("TEST_EXECUTION")));
    }

    @Test
    void rejectsMissingCredentialWrongPurposeAndCallerOwnedQueueSelectors() throws Exception {
        DurableTestWorkerAcquisitionService service =
                mock(DurableTestWorkerAcquisitionService.class);
        MockMvc mvc = mvc(service, Set.of("TEST_EXECUTION"));

        mvc.perform(post("/api/testing/durable-executions/worker-acquisitions")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(requestJson()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code")
                        .value("RG.INTEGRATION.AUTHENTICATION_REQUIRED"));
        mvc.perform(post("/api/testing/durable-executions/worker-acquisitions")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_FIXTURE_READ")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(requestJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code")
                        .value("RG.INTEGRATION.PURPOSE_FORBIDDEN"));
        mvc.perform(post("/api/testing/durable-executions/worker-acquisitions")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "schemaVersion": "bloge.durableTestWorkerAcquisitionRequest.v1",
                                  "clientRequestId": "poll-1",
                                  "runId": "caller-selected-run"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RG.TEST.REQUEST_MALFORMED"));

        verifyNoInteractions(service);
    }

    private static String requestJson() {
        return """
                {
                  "schemaVersion": "bloge.durableTestWorkerAcquisitionRequest.v1",
                  "clientRequestId": "poll-1"
                }
                """;
    }

    private static MockMvc mvc(
            DurableTestWorkerAcquisitionService service, Set<String> purposes) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "test-runtime", "tenant-a", "org-a", "project-a", "test", "local",
                "WORKLOAD", "runner", "", purposes, Instant.MAX, true,
                Set.of("quality"), "CONFIDENTIAL", "", Instant.MAX);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("test-token", identity, false),
                new RecordingAudit());
        return MockMvcBuilders.standaloneSetup(
                        new DurableTestWorkerAcquisitionController(service, authenticator))
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
