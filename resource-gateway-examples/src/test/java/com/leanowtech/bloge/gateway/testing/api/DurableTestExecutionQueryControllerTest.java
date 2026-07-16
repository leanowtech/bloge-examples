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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DurableTestExecutionQueryControllerTest {

    @Test
    void authenticatesDedicatedReadAndReturnsPayloadFreeProjection() throws Exception {
        DurableTestExecutionQueryService service = mock(DurableTestExecutionQueryService.class);
        when(service.find(eq("run-a"), any())).thenReturn(response());
        MockMvc mvc = mvc(service, Set.of("TEST_EXECUTION"));

        mvc.perform(get("/api/testing/durable-executions/run-a")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion")
                        .value(DurableTestExecutionQueryResponse.SCHEMA_VERSION))
                .andExpect(jsonPath("$.runId").value("run-a"))
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.target.id").value("graph-a"))
                .andExpect(jsonPath("$.fixture.fixtureBundleId").value("fixture-a"))
                .andExpect(jsonPath("$.engineBoundary.nodeId").value("approval"))
                .andExpect(jsonPath("$.context").doesNotExist())
                .andExpect(jsonPath("$.fixtureBundle").doesNotExist())
                .andExpect(jsonPath("$.providerState").doesNotExist())
                .andExpect(jsonPath("$.engineState").doesNotExist())
                .andExpect(jsonPath("$.replayPayloads").doesNotExist());

        verify(service).find(eq("run-a"),
                org.mockito.ArgumentMatchers.argThat(identity ->
                        identity.tenantId().equals("tenant-a")
                                && identity.purpose().equals("TEST_EXECUTION")));
    }

    @Test
    void rejectsMissingCredentialAndUnapprovedPurposeBeforeServiceEntry() throws Exception {
        DurableTestExecutionQueryService service = mock(DurableTestExecutionQueryService.class);
        MockMvc mvc = mvc(service, Set.of("TEST_EXECUTION"));

        mvc.perform(get("/api/testing/durable-executions/run-a")
                        .header("X-Purpose", "TEST_EXECUTION"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code")
                        .value("RG.INTEGRATION.AUTHENTICATION_REQUIRED"));
        mvc.perform(get("/api/testing/durable-executions/run-a")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_FIXTURE_READ"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.PURPOSE_FORBIDDEN"));

        verifyNoInteractions(service);
    }

    private static DurableTestExecutionQueryResponse response() {
        return new DurableTestExecutionQueryResponse(
                "", "run-a", "engine-a", "SUSPENDED",
                new DurableTestExecutionQueryResponse.Fence("owner-a", 2, 5),
                Instant.parse("2026-07-17T00:03:00Z"),
                new DurableTestExecutionQueryResponse.Target("GRAPH", "graph-a", sha('a')),
                new DurableTestExecutionQueryResponse.Fixture("fixture-a", 3, sha('f')),
                "GRAPH_CONTRACT_TEST", "DENY_REAL", sha('b'), sha('d'), sha('c'),
                new DurableTestExecutionQueryResponse.EngineBoundary(
                        "checkpoint-a", "approval", "SUSPEND", 4, 7, sha('e')),
                sha('a'), Instant.parse("2026-07-17T00:00:00Z"),
                Instant.parse("2026-07-17T00:01:00Z"), true, false);
    }

    private static MockMvc mvc(
            DurableTestExecutionQueryService service, Set<String> purposes) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "test-runtime", "tenant-a", "org-a", "project-a", "test", "local",
                "WORKLOAD", "runner", "", purposes, Instant.MAX, true,
                Set.of("quality"), "CONFIDENTIAL", "", Instant.MAX);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("test-token", identity, false),
                new RecordingAudit());
        return MockMvcBuilders.standaloneSetup(
                        new DurableTestExecutionQueryController(service, authenticator))
                .setControllerAdvice(new TestExecutionProblemHandler()).build();
    }

    private static String sha(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
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
