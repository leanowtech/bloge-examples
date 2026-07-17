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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DurableTestRecoveryStepControllerTest {

    @Test
    void authenticatesDedicatedOperationAndReturnsPayloadFreeSuspension() throws Exception {
        DurableTestTerminalRecoveryService service = mock(
                DurableTestTerminalRecoveryService.class);
        when(service.advance(eq("run-a"), any(), any())).thenReturn(suspendedResponse(false));
        MockMvc mvc = mvc(service, Set.of("TEST_EXECUTION"));

        mvc.perform(post("/api/testing/durable-executions/run-a/recovery-steps")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(requestJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion")
                        .value(DurableTestRecoveryStepResponse.SCHEMA_VERSION))
                .andExpect(jsonPath("$.outcome").value("SUSPENDED"))
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.boundary.nodeId").value("approval-2"))
                .andExpect(jsonPath("$.terminal").isEmpty())
                .andExpect(jsonPath("$.signal").doesNotExist())
                .andExpect(jsonPath("$.engineState").doesNotExist())
                .andExpect(jsonPath("$.leaseExpiresAt").doesNotExist());

        verify(service).advance(eq("run-a"), any(),
                org.mockito.ArgumentMatchers.argThat(identity ->
                        identity.actorId().equals("worker-a")));
    }

    @Test
    void rejectsWrongPurposeAndCallerOwnedExecutionFieldsBeforeServiceEntry()
            throws Exception {
        DurableTestTerminalRecoveryService service = mock(
                DurableTestTerminalRecoveryService.class);
        MockMvc mvc = mvc(service, Set.of("TEST_EXECUTION"));
        String forgedOutcome = requestJson().replace(
                "\"signal\": {", "\"outcome\": \"COMPLETED\", \"signal\": {");
        String forgedLease = requestJson().replace(
                "\"revision\": 8", "\"revision\": 8, \"leaseExpiresAt\": \"2099-01-01T00:00:00Z\"");

        mvc.perform(post("/api/testing/durable-executions/run-a/recovery-steps")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_FIXTURE_READ")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(requestJson()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/testing/durable-executions/run-a/recovery-steps")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(forgedOutcome))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RG.TEST.REQUEST_MALFORMED"));
        mvc.perform(post("/api/testing/durable-executions/run-a/recovery-steps")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(forgedLease))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RG.TEST.REQUEST_MALFORMED"));

        verifyNoInteractions(service);
    }

    private static DurableTestRecoveryStepResponse suspendedResponse(boolean replay) {
        return new DurableTestRecoveryStepResponse(
                "", "run-a", "SUSPENDED", "SUSPENDED", "recovery-a", 4, 9,
                Instant.parse("2026-07-17T00:02:00Z"), "sha256:" + "b".repeat(64),
                new DurableTestRecoveryStepResponse.Boundary(
                        "approval-2", "SUSPEND", 5, 5),
                null, replay);
    }

    private static String requestJson() {
        return """
                {
                  "schemaVersion": "bloge.durableTestRecoveryStepRequest.v1",
                  "clientRequestId": "step-1",
                  "expectedFence": {
                    "ownerId": "recovery-a",
                    "leaseEpoch": 4,
                    "revision": 8
                  },
                  "expectedCheckpointFingerprint": "sha256:%s",
                  "signal": {"nodeId": "approval-1", "data": "approved"}
                }
                """.formatted("a".repeat(64));
    }

    private static MockMvc mvc(
            DurableTestTerminalRecoveryService service, Set<String> purposes) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "test-runtime", "tenant-a", "org-a", "project-a", "test", "sg",
                "WORKLOAD", "worker-a", "dispatcher-a", purposes, Instant.MAX, true,
                Set.of("quality"), "CONFIDENTIAL", "grant-a", Instant.MAX);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("test-token", identity, false),
                new RecordingAudit());
        return MockMvcBuilders.standaloneSetup(
                        new DurableTestRecoveryStepController(service, authenticator))
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
