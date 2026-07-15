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
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TestExecutionControllerTest {

    @Test
    void httpSurfaceRequiresVerifiedTestExecutionIdentity() throws Exception {
        TestExecutionApiService service = mock(TestExecutionApiService.class);
        MockMvc mvc = mvc(service);

        mvc.perform(get("/api/testing/executions/run-1").header("X-Purpose", "TEST_EXECUTION"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"resource-gateway-testing\""))
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.AUTHENTICATION_REQUIRED"));
    }

    @Test
    void verifiedRequestUsesServerIdentityAndReturnsStoredRun() throws Exception {
        TestExecutionApiService service = mock(TestExecutionApiService.class);
        TestExecutionApiResponse response = new TestExecutionApiResponse("", "run-1",
                new TestExecutionApiRequest.Target("GRAPH", "graph-a", "sha256:target"),
                new TestExecutionApiResponse.ResolvedFixtureBundleRef("STORED", "fixture-a", 1,
                        "sha256:fixture"), null, null);
        when(service.find(eq("run-1"), eq(TestExecutionApiRequest.Verbosity.SUMMARY), any()))
                .thenReturn(response);
        MockMvc mvc = mvc(service);

        mvc.perform(get("/api/testing/executions/run-1")
                        .queryParam("verbosity", "SUMMARY")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-1"))
                .andExpect(jsonPath("$.target.id").value("graph-a"));

        verify(service).find(eq("run-1"), eq(TestExecutionApiRequest.Verbosity.SUMMARY),
                org.mockito.ArgumentMatchers.argThat(identity -> identity.tenantId().equals("tenant-a")
                        && identity.environmentId().equals("test")
                        && identity.actorId().equals("runner")));
    }

    @Test
    void malformedJsonUsesStableTestingProblemContract() throws Exception {
        MockMvc mvc = mvc(mock(TestExecutionApiService.class));

        mvc.perform(post("/api/testing/executions")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RG.TEST.REQUEST_MALFORMED"));
    }

    @Test
    void operatorDiscoveryAndExecutionUseDedicatedPathsAndPurposes() throws Exception {
        TestExecutionApiService service = mock(TestExecutionApiService.class);
        TestExecutionApiRequest.Target target = new TestExecutionApiRequest.Target(
                "OPERATOR", "customer.normalize", "sha256:target");
        when(service.describeOperatorTarget(eq("customer.normalize"), any())).thenReturn(
                new TestOperatorTargetDescriptor("", target, "sha256:implementation", "sha256:state",
                        "sha256:schema",
                        Map.of(), Map.of(), "SYNCHRONOUS", "READ_ONLY", "IDEMPOTENT", Map.of(),
                        "EXECUTABLE_UNIT", Map.of(), "NONE_DECLARED", true, true, List.of(), List.of()));
        TestExecutionApiResponse response = new TestExecutionApiResponse("", "run-operator-1", target,
                new TestExecutionApiResponse.ResolvedFixtureBundleRef(
                        "INLINE", "fixture-a", 1, "sha256:fixture"), null, null);
        when(service.executeOperator(eq("customer.normalize"), any(), any())).thenReturn(response);
        MockMvc mvc = mvc(service);

        mvc.perform(get("/api/testing/targets/operators/customer.normalize")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.target.kind").value("OPERATOR"))
                .andExpect(jsonPath("$.testabilityClass").value("EXECUTABLE_UNIT"));
        mvc.perform(post("/api/testing/targets/operators/customer.normalize/executions")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"schemaVersion":"bloge.testOperatorExecutionRequest.v1",
                                 "target":{"kind":"OPERATOR","id":"customer.normalize","fingerprint":""},
                                 "executionPurpose":"OPERATOR_UNIT_TEST","input":{"value":"Ada"},
                                 "fixtureBundle":null,
                                 "fixtureBundleRef":{"fixtureBundleId":"fixture-a","revision":1,"fingerprint":""},
                                 "verbosity":"STANDARD","metadata":{}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-operator-1"));

        verify(service).describeOperatorTarget(eq("customer.normalize"), any());
        verify(service).executeOperator(eq("customer.normalize"), any(), any());
    }

    @Test
    void executionPurposeCannotWriteGovernedFixtureRevisions() throws Exception {
        TestExecutionApiService service = mock(TestExecutionApiService.class);
        MockMvc mvc = mvc(service);

        mvc.perform(put("/api/testing/fixture-bundles/fixture-a")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.PURPOSE_FORBIDDEN"));

        org.mockito.Mockito.verifyNoInteractions(service);
    }

    private static MockMvc mvc(TestExecutionApiService service) {
        RecordingAudit audit = new RecordingAudit();
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "test-runtime", "tenant-a", "org-a", "project-a", "test", "local",
                "WORKLOAD", "runner", "", Set.of("TEST_EXECUTION"), Instant.MAX, true,
                Set.of("quality"), "CONFIDENTIAL", "", Instant.MAX);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("test-token", identity, false), audit);
        return MockMvcBuilders.standaloneSetup(new TestExecutionController(service, authenticator))
                .setControllerAdvice(new TestExecutionProblemHandler()).build();
    }

    private static final class RecordingAudit implements IntegrationAccessAuditRepository {
        private final List<IntegrationAccessAuditRecord> records = new ArrayList<>();
        @Override public IntegrationAccessAuditRecord append(IntegrationAccessAuditRecord record) {
            IntegrationAccessAuditRecord stored = record.withSequence(records.size() + 1L);
            records.add(stored);
            return stored;
        }
        @Override public List<IntegrationAccessAuditRecord> recent(int limit) { return List.copyOf(records); }
    }
}
