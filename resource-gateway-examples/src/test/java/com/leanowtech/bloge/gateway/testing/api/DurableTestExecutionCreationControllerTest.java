package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRecord;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationWorkloadIdentity;
import com.leanowtech.bloge.gateway.integration.StaticBearerIntegrationIdentityResolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
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

class DurableTestExecutionCreationControllerTest {

    @Test
    void authenticatesDedicatedCreateAndReturnsNestedPayloadFreeView() throws Exception {
        DurableTestExecutionCreationService service = mock(
                DurableTestExecutionCreationService.class);
        when(service.create(any(), any())).thenReturn(response());
        MockMvc mvc = mvc(service, Set.of("TEST_EXECUTION"));

        mvc.perform(post("/api/testing/durable-executions")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion")
                        .value(DurableTestExecutionCreateResponse.SCHEMA_VERSION))
                .andExpect(jsonPath("$.execution.schemaVersion")
                        .value(DurableTestExecutionQueryResponse.SCHEMA_VERSION))
                .andExpect(jsonPath("$.execution.runId").value("run-a"))
                .andExpect(jsonPath("$.execution.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.execution.engineBoundary.nodeId")
                        .value("approval"))
                .andExpect(jsonPath("$.idempotentReplay").value(false))
                .andExpect(jsonPath("$.execution.context").doesNotExist())
                .andExpect(jsonPath("$.execution.fixtureBundle").doesNotExist())
                .andExpect(jsonPath("$.execution.providerState").doesNotExist());

        verify(service).create(
                org.mockito.ArgumentMatchers.argThat(request ->
                        request.clientRequestId().equals("create-1")
                                && request.target().fingerprint().equals(sha('a'))
                                && request.fixtureBundleRef().revision() == 3
                                && request.context().containsKey("customerId")),
                org.mockito.ArgumentMatchers.argThat(identity ->
                        identity.tenantId().equals("tenant-a")
                                && identity.purpose().equals("TEST_EXECUTION")));
    }

    @Test
    void rejectsMissingCredentialAndUnapprovedPurposeBeforeCreation() throws Exception {
        DurableTestExecutionCreationService service = mock(
                DurableTestExecutionCreationService.class);
        MockMvc mvc = mvc(service, Set.of("TEST_EXECUTION"));

        mvc.perform(post("/api/testing/durable-executions")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code")
                        .value("RG.INTEGRATION.AUTHENTICATION_REQUIRED"));
        mvc.perform(post("/api/testing/durable-executions")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_FIXTURE_READ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code")
                        .value("RG.INTEGRATION.PURPOSE_FORBIDDEN"));

        verifyNoInteractions(service);
    }

    @Test
    void authenticatesPathBoundDurableOperatorCreation() throws Exception {
        DurableTestExecutionCreationService service = mock(
                DurableTestExecutionCreationService.class);
        when(service.createOperator(any(), any(), any())).thenReturn(
                response("OPERATOR", "operator-a", "OPERATOR_UNIT_TEST"));
        MockMvc mvc = mvc(service, Set.of("TEST_EXECUTION"));

        mvc.perform(post("/api/testing/durable-executions/operators/operator-a")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(operatorRequestJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.execution.target.kind").value("OPERATOR"))
                .andExpect(jsonPath("$.execution.target.id").value("operator-a"))
                .andExpect(jsonPath("$.execution.authorizedPurpose")
                        .value("OPERATOR_UNIT_TEST"))
                .andExpect(jsonPath("$.execution.context").doesNotExist());

        verify(service).createOperator(
                org.mockito.ArgumentMatchers.eq("operator-a"),
                org.mockito.ArgumentMatchers.argThat(request ->
                        request.clientRequestId().equals("create-operator-1")
                                && request.target().kind().equals("OPERATOR")
                                && request.target().id().equals("operator-a")
                                && request.input() instanceof java.util.Map<?, ?>),
                org.mockito.ArgumentMatchers.argThat(identity ->
                        identity.tenantId().equals("tenant-a")
                                && identity.purpose().equals("TEST_EXECUTION")));
    }

    private static String requestJson() {
        return """
                {
                  "schemaVersion": "bloge.durableTestExecutionCreateRequest.v1",
                  "clientRequestId": "create-1",
                  "target": {"kind": "GRAPH", "id": "graph-a", "fingerprint": "%s"},
                  "executionPurpose": "GRAPH_CONTRACT_TEST",
                  "context": {"customerId": "c-1"},
                  "fixtureBundleRef": {
                    "fixtureBundleId": "fixture-a", "revision": 3, "fingerprint": "%s"
                  }
                }
                """.formatted(sha('a'), sha('f'));
    }

    private static String operatorRequestJson() {
        return """
                {
                  "schemaVersion": "bloge.durableOperatorTestExecutionCreateRequest.v1",
                  "clientRequestId": "create-operator-1",
                  "target": {"kind": "OPERATOR", "id": "operator-a", "fingerprint": "%s"},
                  "executionPurpose": "OPERATOR_UNIT_TEST",
                  "input": {"customerId": "c-1"},
                  "fixtureBundleRef": {
                    "fixtureBundleId": "fixture-a", "revision": 3, "fingerprint": "%s"
                  }
                }
                """.formatted(sha('a'), sha('f'));
    }

    private static DurableTestExecutionCreateResponse response() {
        return response("GRAPH", "graph-a", "GRAPH_CONTRACT_TEST");
    }

    private static DurableTestExecutionCreateResponse response(
            String targetKind, String targetId, String purpose) {
        return new DurableTestExecutionCreateResponse("",
                new DurableTestExecutionQueryResponse(
                        "", "run-a", "engine-a", "SUSPENDED",
                        new DurableTestExecutionQueryResponse.Fence("owner-a", 1, 0),
                        Instant.parse("2026-07-17T00:03:00Z"),
                        new DurableTestExecutionQueryResponse.Target(
                                targetKind, targetId, sha('a')),
                        new DurableTestExecutionQueryResponse.Fixture(
                                "fixture-a", 3, sha('f')),
                        purpose, "DENY_REAL", sha('b'), sha('d'), sha('c'),
                        new DurableTestExecutionQueryResponse.EngineBoundary(
                                "checkpoint-a", "approval", "SUSPEND", 1, 7, sha('e')),
                        sha('a'), Instant.parse("2026-07-17T00:00:00Z"),
                        Instant.parse("2026-07-17T00:01:00Z"), true, false), false);
    }

    private static MockMvc mvc(
            DurableTestExecutionCreationService service, Set<String> purposes) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "test-runtime", "tenant-a", "org-a", "project-a", "test", "local",
                "WORKLOAD", "runner", "", purposes, Instant.MAX, true,
                Set.of("quality"), "CONFIDENTIAL", "", Instant.MAX);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("test-token", identity, false),
                new RecordingAudit());
        return MockMvcBuilders.standaloneSetup(
                        new DurableTestExecutionCreationController(service, authenticator))
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
