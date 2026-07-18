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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TestSuiteStabilityControllerTest {
    @Test
    void stabilityExecutionUsesDedicatedPathAndSuiteExecutionAuthority() throws Exception {
        TestSuiteStabilityExecutionService service =
                mock(TestSuiteStabilityExecutionService.class);
        when(service.execute(eq("orders-suite"), any(), any())).thenReturn(null);
        MockMvc mvc = mvc(service, Set.of("TEST_EXECUTION"));

        mvc.perform(post("/api/testing/suites/orders-suite/stability-executions")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request()))
                .andExpect(status().isOk());

        verify(service).execute(eq("orders-suite"),
                org.mockito.ArgumentMatchers.argThat(request ->
                        request.schemaVersion().equals(
                                TestSuiteStabilityExecutionRequest.SCHEMA_VERSION_V1)
                                && request.suiteRef().revision() == 7
                                && request.clientRequestId().equals("stability-ci-42")
                                && request.attempts() == 5),
                org.mockito.ArgumentMatchers.argThat(identity ->
                        identity.purpose().equals("TEST_EXECUTION")
                                && identity.tenantId().equals("tenant-a")
                                && identity.environmentId().equals("test")));
    }

    @Test
    void statisticalRequestBindsTheCompletePrecommittedPolicy() throws Exception {
        TestSuiteStabilityExecutionService service =
                mock(TestSuiteStabilityExecutionService.class);
        when(service.execute(eq("orders-suite"), any(), any())).thenReturn(null);
        MockMvc mvc = mvc(service, Set.of("TEST_EXECUTION"));

        mvc.perform(post("/api/testing/suites/orders-suite/stability-executions")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statisticalRequest()))
                .andExpect(status().isOk());

        verify(service).execute(eq("orders-suite"),
                org.mockito.ArgumentMatchers.argThat(request ->
                        request.schemaVersion().equals(
                                TestSuiteStabilityExecutionRequest.SCHEMA_VERSION)
                                && request.attempts() == 29
                                && request.statisticalPolicy() != null
                                && request.statisticalPolicy().confidenceLevelBps() == 9_500
                                && request.statisticalPolicy()
                                .maximumInstabilityRateBps() == 1_000), any());
    }

    @Test
    void retainedStabilityReadUsesTheSameExecutionAuthority() throws Exception {
        TestSuiteStabilityExecutionService service =
                mock(TestSuiteStabilityExecutionService.class);
        when(service.find(eq("stability-" + "a".repeat(64)), any())).thenReturn(null);
        MockMvc mvc = mvc(service, Set.of("TEST_EXECUTION"));

        mvc.perform(get("/api/testing/stability-executions/stability-" + "a".repeat(64))
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION"))
                .andExpect(status().isOk());

        verify(service).find(eq("stability-" + "a".repeat(64)),
                org.mockito.ArgumentMatchers.argThat(identity ->
                        identity.purpose().equals("TEST_EXECUTION")));
    }

    @Test
    void suiteWritePurposeCannotInvokeStabilityRuntime() throws Exception {
        TestSuiteStabilityExecutionService service =
                mock(TestSuiteStabilityExecutionService.class);
        MockMvc mvc = mvc(service, Set.of("TEST_SUITE_WRITE"));

        mvc.perform(post("/api/testing/suites/orders-suite/stability-executions")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_SUITE_WRITE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.PURPOSE_FORBIDDEN"));

        verifyNoInteractions(service);
    }

    private static MockMvc mvc(
            TestSuiteStabilityExecutionService service,
            Set<String> purposes) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "test-runtime", "tenant-a", "org-a", "project-a", "test", "local",
                "WORKLOAD", "runner", "", purposes, Instant.MAX, true,
                Set.of("quality"), "CONFIDENTIAL", "", Instant.MAX);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("test-token", identity, false),
                new RecordingAudit());
        return MockMvcBuilders.standaloneSetup(
                        new TestSuiteStabilityController(service, authenticator))
                .setControllerAdvice(new TestExecutionProblemHandler())
                .build();
    }

    private static String request() {
        return """
                {"schemaVersion":"bloge.testSuiteStabilityExecutionRequest.v1",
                 "suiteRef":{"suiteId":"orders-suite","revision":7,
                 "fingerprint":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                 "clientRequestId":"stability-ci-42","attempts":5,
                 "metadata":{"pipeline":"nightly"}}
                """;
    }

    private static String statisticalRequest() {
        return """
                {"schemaVersion":"bloge.testSuiteStabilityExecutionRequest.v2",
                 "suiteRef":{"suiteId":"orders-suite","revision":7,
                 "fingerprint":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                 "clientRequestId":"stability-statistical-ci-42","attempts":29,
                 "statisticalPolicy":{"model":"ZERO_INSTABILITY_EXACT_BINOMIAL",
                 "claimScope":"SUITE_ATTEMPT_ANY_CASE",
                 "stoppingRule":"PRECOMMITTED_FIXED_HORIZON",
                 "censoringPolicy":"FAIL_CLOSED","confidenceLevelBps":9500,
                 "maximumInstabilityRateBps":1000},
                 "metadata":{"pipeline":"nightly"}}
                """;
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
