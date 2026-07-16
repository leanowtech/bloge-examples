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

class DurableTestOwnerClaimControllerTest {

    @Test
    void authenticatesTheDedicatedOperationAndReturnsOnlyPayloadFreeFenceFacts() throws Exception {
        DurableTestOwnerClaimService service = mock(DurableTestOwnerClaimService.class);
        when(service.claim(eq("run-a"), any(), any())).thenReturn(
                new DurableTestOwnerClaimResponse("", "run-a", "RESUMING",
                        "recovery-instance-a", 4, 8,
                        Instant.parse("2026-07-16T00:03:00Z"),
                        "sha256:" + "b".repeat(64),
                        new DurableTestOwnerClaimResponse.Target(
                                "GRAPH", "graph-a", "sha256:" + "a".repeat(64)), false));
        MockMvc mvc = mvc(service, Set.of("TEST_EXECUTION"));

        mvc.perform(post("/api/testing/durable-executions/run-a/owner-claims")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(requestJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion")
                        .value(DurableTestOwnerClaimResponse.SCHEMA_VERSION))
                .andExpect(jsonPath("$.runId").value("run-a"))
                .andExpect(jsonPath("$.status").value("RESUMING"))
                .andExpect(jsonPath("$.ownerId").value("recovery-instance-a"))
                .andExpect(jsonPath("$.target.id").value("graph-a"))
                .andExpect(jsonPath("$.fixture").doesNotExist())
                .andExpect(jsonPath("$.replayPayloads").doesNotExist())
                .andExpect(jsonPath("$.engineState").doesNotExist());

        verify(service).claim(eq("run-a"), any(),
                org.mockito.ArgumentMatchers.argThat(identity ->
                        identity.tenantId().equals("tenant-a")
                                && identity.projectId().equals("project-a")
                                && identity.purpose().equals("TEST_EXECUTION")));
    }

    @Test
    void rejectsMissingCredentialWrongPurposeAndUnknownCommandFields() throws Exception {
        DurableTestOwnerClaimService service = mock(DurableTestOwnerClaimService.class);
        MockMvc mvc = mvc(service, Set.of("TEST_EXECUTION"));
        String base = requestJson().stripTrailing();
        String callerOwnedClaim = base.substring(0, base.lastIndexOf('}'))
                + ",\n  \"claimantOwnerId\": \"caller-owned\"\n}";

        mvc.perform(post("/api/testing/durable-executions/run-a/owner-claims")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(requestJson()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code")
                        .value("RG.INTEGRATION.AUTHENTICATION_REQUIRED"));
        mvc.perform(post("/api/testing/durable-executions/run-a/owner-claims")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_FIXTURE_READ")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(requestJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.PURPOSE_FORBIDDEN"));
        mvc.perform(post("/api/testing/durable-executions/run-a/owner-claims")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(callerOwnedClaim))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RG.TEST.REQUEST_MALFORMED"));

        verifyNoInteractions(service);
    }

    private static String requestJson() {
        return """
                {
                  "schemaVersion": "bloge.durableTestOwnerClaimRequest.v1",
                  "clientRequestId": "request-1",
                  "expectedFence": {
                    "ownerId": "old-owner",
                    "leaseEpoch": 3,
                    "revision": 7
                  },
                  "expectedCheckpointFingerprint": "sha256:%s"
                }
                """.formatted("a".repeat(64));
    }

    private static MockMvc mvc(DurableTestOwnerClaimService service, Set<String> purposes) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "test-runtime", "tenant-a", "org-a", "project-a", "test", "local",
                "WORKLOAD", "runner", "", purposes, Instant.MAX, true,
                Set.of("quality"), "CONFIDENTIAL", "", Instant.MAX);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("test-token", identity, false),
                new RecordingAudit());
        return MockMvcBuilders.standaloneSetup(
                        new DurableTestOwnerClaimController(service, authenticator))
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
