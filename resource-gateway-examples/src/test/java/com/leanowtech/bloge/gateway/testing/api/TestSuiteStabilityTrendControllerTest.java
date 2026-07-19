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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TestSuiteStabilityTrendControllerTest {
    @Test
    void trendEndpointBindsExactWindowAndDedicatedReadAuthority() throws Exception {
        TestSuiteStabilityTrendAnalysisService service =
                mock(TestSuiteStabilityTrendAnalysisService.class);
        when(service.analyze(eq("orders-suite"), any(), any())).thenReturn(null);
        MockMvc mvc = mvc(service, Set.of("TEST_EXECUTION"));

        mvc.perform(post("/api/testing/suites/orders-suite/stability-trend-analyses")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request()))
                .andExpect(status().isOk());

        verify(service).analyze(eq("orders-suite"),
                org.mockito.ArgumentMatchers.argThat(value ->
                        value.schemaVersion().equals(
                                TestSuiteStabilityTrendAnalysisRequest.SCHEMA_VERSION)
                                && value.suiteRef().revision() == 7
                                && value.fromInclusive().equals(
                                Instant.parse("2026-07-18T00:00:00Z"))
                                && value.toExclusive().equals(
                                Instant.parse("2026-07-19T00:00:00Z"))
                                && value.minimumRuns() == 3
                                && value.maximumRuns() == 20),
                org.mockito.ArgumentMatchers.argThat(identity ->
                        identity.purpose().equals("TEST_EXECUTION")
                                && identity.tenantId().equals("tenant-a")
                                && identity.environmentId().equals("test")));
    }

    @Test
    void suiteReadPurposeCannotAccessExecutionHistoryTrend() throws Exception {
        TestSuiteStabilityTrendAnalysisService service =
                mock(TestSuiteStabilityTrendAnalysisService.class);
        MockMvc mvc = mvc(service, Set.of("TEST_SUITE_READ"));

        mvc.perform(post("/api/testing/suites/orders-suite/stability-trend-analyses")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_SUITE_READ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.PURPOSE_FORBIDDEN"));

        verifyNoInteractions(service);
    }

    private static MockMvc mvc(
            TestSuiteStabilityTrendAnalysisService service,
            Set<String> purposes) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "test-runtime", "tenant-a", "org-a", "project-a", "test", "local",
                "WORKLOAD", "runner", "", purposes, Instant.MAX, true,
                Set.of("quality"), "CONFIDENTIAL", "", Instant.MAX);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("test-token", identity, false),
                new RecordingAudit());
        return MockMvcBuilders.standaloneSetup(
                        new TestSuiteStabilityTrendController(service, authenticator))
                .setControllerAdvice(new TestExecutionProblemHandler())
                .build();
    }

    private static String request() {
        return """
                {"schemaVersion":"bloge.testSuiteStabilityTrendAnalysisRequest.v1",
                 "suiteRef":{"suiteId":"orders-suite","revision":7,
                 "fingerprint":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                 "fromInclusive":"2026-07-18T00:00:00Z",
                 "toExclusive":"2026-07-19T00:00:00Z",
                 "minimumRuns":3,"maximumRuns":20}
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
