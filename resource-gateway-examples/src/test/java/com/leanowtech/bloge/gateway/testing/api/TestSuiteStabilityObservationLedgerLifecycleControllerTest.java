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

class TestSuiteStabilityObservationLedgerLifecycleControllerTest {
    @Test
    void endpointBindsGenerationPinsAndDedicatedTrendReadAuthority() throws Exception {
        TestSuiteStabilityObservationLedgerLifecyclePageService service =
                mock(TestSuiteStabilityObservationLedgerLifecyclePageService.class);
        when(service.read(eq("orders-suite"), any(), any())).thenReturn(null);
        MockMvc mvc = mvc(service, Set.of("TEST_REPLAY"));

        mvc.perform(post("/api/testing/suites/orders-suite/"
                        + "stability-observation-ledger-lifecycle-pages")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_REPLAY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request()))
                .andExpect(status().isOk());

        verify(service).read(eq("orders-suite"),
                org.mockito.ArgumentMatchers.argThat(value ->
                        value.afterRetirementGeneration() == 40
                                && value.maximumRetirements() == 5
                                && value.expectedCurrentFloorFingerprint().equals(
                                "sha256:" + "b".repeat(64))
                                && value.expectedHeadFingerprint().equals(
                                "sha256:" + "c".repeat(64))),
                org.mockito.ArgumentMatchers.argThat(identity ->
                        identity.purpose().equals("TEST_REPLAY")
                                && identity.tenantId().equals("tenant-a")
                                && identity.environmentId().equals("test")));
    }

    @Test
    void unrelatedReadPurposeCannotReachLifecycleService() throws Exception {
        TestSuiteStabilityObservationLedgerLifecyclePageService service =
                mock(TestSuiteStabilityObservationLedgerLifecyclePageService.class);
        MockMvc mvc = mvc(service, Set.of("TEST_SUITE_READ"));

        mvc.perform(post("/api/testing/suites/orders-suite/"
                        + "stability-observation-ledger-lifecycle-pages")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_SUITE_READ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.PURPOSE_FORBIDDEN"));

        verifyNoInteractions(service);
    }

    private static MockMvc mvc(
            TestSuiteStabilityObservationLedgerLifecyclePageService service,
            Set<String> purposes) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "test-runtime", "tenant-a", "org-a", "project-a", "test", "local",
                "WORKLOAD", "runner", "", purposes, Instant.MAX, true,
                Set.of("quality"), "CONFIDENTIAL", "", Instant.MAX);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("test-token", identity, false),
                new RecordingAudit());
        return MockMvcBuilders.standaloneSetup(
                        new TestSuiteStabilityObservationLedgerLifecycleController(
                                service, authenticator))
                .setControllerAdvice(new TestExecutionProblemHandler())
                .build();
    }

    private static String request() {
        return """
                {"schemaVersion":"bloge.testSuiteStabilityObservationLedgerLifecyclePageRequest.v1",
                 "suiteRef":{"suiteId":"orders-suite","revision":7,
                 "fingerprint":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                 "afterRetirementGeneration":40,"maximumRetirements":5,
                 "expectedCurrentFloorFingerprint":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                 "expectedHeadFingerprint":"sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}
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
