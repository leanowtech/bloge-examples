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

class DurableTestRecoverySequenceControllerTest {

    @Test
    void authenticatesDedicatedOperationAndReturnsPayloadFreeOrderedSteps()
            throws Exception {
        DurableTestRecoverySequenceService service = mock(
                DurableTestRecoverySequenceService.class);
        DurableTestRecoveryStepResponse suspended = suspended();
        DurableTestRecoveryStepResponse terminal = terminal();
        when(service.advance(eq("run-a"), any(), any())).thenReturn(
                new DurableTestRecoverySequenceResponse(
                        "", "run-a", "COMPLETED", "TERMINAL", "TERMINAL",
                        3, 2, List.of(suspended, terminal), false));
        MockMvc mvc = mvc(service, Set.of("TEST_EXECUTION"));

        mvc.perform(post("/api/testing/durable-executions/run-a/recovery-sequences")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(requestJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion")
                        .value(DurableTestRecoverySequenceResponse.SCHEMA_VERSION))
                .andExpect(jsonPath("$.outcome").value("COMPLETED"))
                .andExpect(jsonPath("$.stopReason").value("TERMINAL"))
                .andExpect(jsonPath("$.providedSignalCount").value(3))
                .andExpect(jsonPath("$.consumedSignalCount").value(2))
                .andExpect(jsonPath("$.steps[0].boundary.nodeId").value("approval-2"))
                .andExpect(jsonPath("$.steps[1].terminal.executionOutcome")
                        .value("COMPLETED"))
                .andExpect(jsonPath("$.signals").doesNotExist())
                .andExpect(jsonPath("$.dispatch").doesNotExist())
                .andExpect(jsonPath("$.engineState").doesNotExist());

        verify(service).advance(eq("run-a"), any(),
                org.mockito.ArgumentMatchers.argThat(identity ->
                        identity.actorId().equals("worker-a")));
    }

    @Test
    void rejectsWrongPurposeAndCallerOwnedOrchestrationFieldsBeforeServiceEntry()
            throws Exception {
        DurableTestRecoverySequenceService service = mock(
                DurableTestRecoverySequenceService.class);
        MockMvc mvc = mvc(service, Set.of("TEST_EXECUTION"));
        String forgedResult = requestJson().replace(
                "\"signals\": [", "\"outcome\": \"COMPLETED\", \"signals\": [");
        String forgedSignalControl = requestJson().replace(
                "\"nodeId\": \"approval-1\"",
                "\"nodeId\": \"approval-1\", \"retry\": true");

        mvc.perform(post("/api/testing/durable-executions/run-a/recovery-sequences")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_FIXTURE_READ")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(requestJson()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/testing/durable-executions/run-a/recovery-sequences")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(forgedResult))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RG.TEST.REQUEST_MALFORMED"));
        mvc.perform(post("/api/testing/durable-executions/run-a/recovery-sequences")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(forgedSignalControl))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RG.TEST.REQUEST_MALFORMED"));

        verifyNoInteractions(service);
    }

    private static DurableTestRecoveryStepResponse suspended() {
        return new DurableTestRecoveryStepResponse(
                "", "run-a", "SUSPENDED", "SUSPENDED", "owner-a", 2, 5,
                Instant.parse("2026-07-17T00:00:00Z"), "sha256:" + "a".repeat(64),
                new DurableTestRecoveryStepResponse.Boundary(
                        "approval-2", "SUSPEND", 5, 5), null, false);
    }

    private static DurableTestRecoveryStepResponse terminal() {
        Instant now = Instant.parse("2026-07-17T00:01:00Z");
        return new DurableTestRecoveryStepResponse(
                "", "run-a", "COMPLETED", "TERMINAL", "owner-b", 3, 7,
                now, "sha256:" + "b".repeat(64),
                new DurableTestRecoveryStepResponse.Boundary(
                        "complete", "NODE_BOUNDARY", 7, 7),
                new DurableTestRecoveryStepResponse.Terminal(
                        "COMPLETED", now, "sha256:" + "c".repeat(64),
                        "EVIDENCE_INCOMPLETE",
                        List.of("PRE_CHECKPOINT_TRACE_UNAVAILABLE")), false);
    }

    private static String requestJson() {
        return """
                {
                  "schemaVersion": "bloge.durableTestRecoverySequenceRequest.v1",
                  "clientRequestId": "sequence-1",
                  "expectedFence": {
                    "ownerId": "owner-initial",
                    "leaseEpoch": 1,
                    "revision": 3
                  },
                  "expectedCheckpointFingerprint": "sha256:%s",
                  "signals": [
                    {"nodeId": "approval-1", "data": "approved"},
                    {"nodeId": "approval-2", "data": "approved"},
                    {"nodeId": "approval-3", "data": "approved"}
                  ]
                }
                """.formatted("a".repeat(64));
    }

    private static MockMvc mvc(
            DurableTestRecoverySequenceService service, Set<String> purposes) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "test-runtime", "tenant-a", "org-a", "project-a", "test", "sg",
                "WORKLOAD", "worker-a", "dispatcher-a", purposes, Instant.MAX, true,
                Set.of("quality"), "CONFIDENTIAL", "grant-a", Instant.MAX);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("test-token", identity, false),
                new RecordingAudit());
        return MockMvcBuilders.standaloneSetup(
                        new DurableTestRecoverySequenceController(service, authenticator))
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
