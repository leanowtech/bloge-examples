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

class DurableTestRecoveryHeartbeatControllerTest {

    @Test
    void authenticatesTheDedicatedOperationAndReturnsOnlyTheSuccessorFence() throws Exception {
        DurableTestRecoveryHeartbeatService service = mock(
                DurableTestRecoveryHeartbeatService.class);
        when(service.heartbeat(eq("run-a"), any(), any())).thenReturn(
                new DurableTestRecoveryHeartbeatResponse("", "run-a", "RESUMING",
                        "recovery-instance-a", 4, 9,
                        Instant.parse("2026-07-17T00:04:00Z"),
                        "sha256:" + "b".repeat(64), false));
        MockMvc mvc = mvc(service, Set.of("TEST_EXECUTION"));

        mvc.perform(post("/api/testing/durable-executions/run-a/heartbeats")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(requestJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion")
                        .value(DurableTestRecoveryHeartbeatResponse.SCHEMA_VERSION))
                .andExpect(jsonPath("$.runId").value("run-a"))
                .andExpect(jsonPath("$.status").value("RESUMING"))
                .andExpect(jsonPath("$.revision").value(9))
                .andExpect(jsonPath("$.dispatch").doesNotExist())
                .andExpect(jsonPath("$.authorization").doesNotExist())
                .andExpect(jsonPath("$.engineState").doesNotExist());

        verify(service).heartbeat(eq("run-a"), any(),
                org.mockito.ArgumentMatchers.argThat(identity ->
                        identity.actorId().equals("worker-a")
                                && identity.purpose().equals("TEST_EXECUTION")));
    }

    @Test
    void rejectsMissingCredentialWrongPurposeAndUnknownFieldsBeforeServiceEntry()
            throws Exception {
        DurableTestRecoveryHeartbeatService service = mock(
                DurableTestRecoveryHeartbeatService.class);
        MockMvc mvc = mvc(service, Set.of("TEST_EXECUTION"));
        String base = requestJson().stripTrailing();
        String callerOwnedLease = base.substring(0, base.lastIndexOf('}'))
                + ",\n  \"leaseDurationSeconds\": 3600\n}";
        String callerOwnedFenceExpiry = requestJson().replace(
                "\"revision\": 8",
                "\"revision\": 8, \"leaseExpiresAt\": \"2099-01-01T00:00:00Z\"");

        mvc.perform(post("/api/testing/durable-executions/run-a/heartbeats")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(requestJson()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code")
                        .value("RG.INTEGRATION.AUTHENTICATION_REQUIRED"));
        mvc.perform(post("/api/testing/durable-executions/run-a/heartbeats")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_FIXTURE_READ")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(requestJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.PURPOSE_FORBIDDEN"));
        mvc.perform(post("/api/testing/durable-executions/run-a/heartbeats")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(callerOwnedLease))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RG.TEST.REQUEST_MALFORMED"));
        mvc.perform(post("/api/testing/durable-executions/run-a/heartbeats")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(callerOwnedFenceExpiry))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RG.TEST.REQUEST_MALFORMED"));

        verifyNoInteractions(service);
    }

    private static String requestJson() {
        return """
                {
                  "schemaVersion": "bloge.durableTestRecoveryHeartbeatRequest.v1",
                  "clientRequestId": "heartbeat-1",
                  "expectedFence": {
                    "ownerId": "recovery-instance-a",
                    "leaseEpoch": 4,
                    "revision": 8
                  },
                  "expectedCheckpointFingerprint": "sha256:%s"
                }
                """.formatted("a".repeat(64));
    }

    private static MockMvc mvc(
            DurableTestRecoveryHeartbeatService service, Set<String> purposes) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "test-runtime", "tenant-a", "org-a", "project-a", "test", "sg",
                "WORKLOAD", "worker-a", "dispatcher-a", purposes, Instant.MAX, true,
                Set.of("quality"), "CONFIDENTIAL", "grant-a", Instant.MAX);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("test-token", identity, false),
                new RecordingAudit());
        return MockMvcBuilders.standaloneSetup(
                        new DurableTestRecoveryHeartbeatController(service, authenticator))
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
