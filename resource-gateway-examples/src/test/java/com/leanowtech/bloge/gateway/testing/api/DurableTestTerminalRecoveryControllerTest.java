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

class DurableTestTerminalRecoveryControllerTest {

    @Test
    void authenticatesDedicatedRecoveryControlAndReturnsPayloadFreeReceipt() throws Exception {
        DurableTestTerminalRecoveryService service = mock(
                DurableTestTerminalRecoveryService.class);
        when(service.recover(eq("run-a"), any(), any())).thenReturn(
                new DurableTestTerminalRecoveryResponse(
                        "", "run-a", "TERMINAL", "COMPLETED", "recovery-a", 4, 9,
                        Instant.parse("2026-07-17T00:02:00Z"), "sha256:" + "a".repeat(64),
                        "sha256:" + "b".repeat(64), "EVIDENCE_INCOMPLETE",
                        List.of("PRE_CHECKPOINT_TRACE_UNAVAILABLE"), false));
        MockMvc mvc = mvc(service, Set.of("TEST_EXECUTION"));

        mvc.perform(post("/api/testing/durable-executions/run-a/terminal-recoveries")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(requestJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion")
                        .value(DurableTestTerminalRecoveryResponse.SCHEMA_VERSION))
                .andExpect(jsonPath("$.status").value("TERMINAL"))
                .andExpect(jsonPath("$.executionOutcome").value("COMPLETED"))
                .andExpect(jsonPath("$.signal").doesNotExist())
                .andExpect(jsonPath("$.dispatch").doesNotExist())
                .andExpect(jsonPath("$.engineState").doesNotExist());

        verify(service).recover(eq("run-a"), any(),
                org.mockito.ArgumentMatchers.argThat(identity ->
                        identity.actorId().equals("worker-a")));
    }

    @Test
    void rejectsWrongPurposeAndCallerOwnedTerminalStateBeforeServiceEntry() throws Exception {
        DurableTestTerminalRecoveryService service = mock(
                DurableTestTerminalRecoveryService.class);
        MockMvc mvc = mvc(service, Set.of("TEST_EXECUTION"));
        String forged = requestJson().replace(
                "\"signal\": {", "\"executionOutcome\": \"COMPLETED\", \"signal\": {");

        mvc.perform(post("/api/testing/durable-executions/run-a/terminal-recoveries")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_FIXTURE_READ")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(requestJson()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/testing/durable-executions/run-a/terminal-recoveries")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(forged))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RG.TEST.REQUEST_MALFORMED"));
        mvc.perform(post("/api/testing/durable-executions/run-a/terminal-recoveries")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(requestJson().replace(", \"data\": \"approved\"", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RG.TEST.REQUEST_MALFORMED"));

        verifyNoInteractions(service);
    }

    private static String requestJson() {
        return """
                {
                  "schemaVersion": "bloge.durableTestTerminalRecoveryRequest.v1",
                  "clientRequestId": "terminal-1",
                  "expectedFence": {
                    "ownerId": "recovery-a",
                    "leaseEpoch": 4,
                    "revision": 8
                  },
                  "expectedCheckpointFingerprint": "sha256:%s",
                  "signal": {"nodeId": "wait", "data": "approved"}
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
                        new DurableTestTerminalRecoveryController(service, authenticator))
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
