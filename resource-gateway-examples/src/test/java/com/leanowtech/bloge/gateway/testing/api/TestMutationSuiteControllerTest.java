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

class TestMutationSuiteControllerTest {

    @Test
    void mutationExecutionUsesDedicatedPathRequestAndSuiteExecutionAuthority() throws Exception {
        TestMutationSuiteExecutionService executions = mock(TestMutationSuiteExecutionService.class);
        when(executions.execute(eq("mutation-suite"), any(), any())).thenReturn(null);
        MockMvc mvc = mvc(executions, Set.of("TEST_EXECUTION"));

        mvc.perform(post("/api/testing/suites/mutation-suite/mutation-executions")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request()))
                .andExpect(status().isOk());

        verify(executions).execute(eq("mutation-suite"),
                org.mockito.ArgumentMatchers.argThat(request ->
                        request.schemaVersion().equals(
                                TestMutationSuiteExecutionRequest.SCHEMA_VERSION)
                                && request.suiteRef().revision() == 2
                                && request.clientRequestId().equals("mutation-ci-42")
                                && request.strategy()
                                == TestMutationSuiteExecutionRequest.Strategy.STOP_AFTER_KILL),
                org.mockito.ArgumentMatchers.argThat(identity ->
                        identity.purpose().equals("TEST_EXECUTION")
                                && identity.tenantId().equals("tenant-a")
                                && identity.environmentId().equals("test")));
    }

    @Test
    void suiteWritePurposeCannotInvokeMutationRuntime() throws Exception {
        TestMutationSuiteExecutionService executions = mock(TestMutationSuiteExecutionService.class);
        MockMvc mvc = mvc(executions, Set.of("TEST_SUITE_WRITE"));

        mvc.perform(post("/api/testing/suites/mutation-suite/mutation-executions")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_SUITE_WRITE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.PURPOSE_FORBIDDEN"));

        verifyNoInteractions(executions);
    }

    private static MockMvc mvc(
            TestMutationSuiteExecutionService executions,
            Set<String> allowedPurposes) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "test-runtime", "tenant-a", "org-a", "project-a", "test", "local",
                "WORKLOAD", "runner", "", allowedPurposes, Instant.MAX, true,
                Set.of("quality"), "CONFIDENTIAL", "", Instant.MAX);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("test-token", identity, false),
                new RecordingAudit());
        return MockMvcBuilders.standaloneSetup(new TestMutationSuiteController(
                        mock(TestMutationSuiteMaterializationService.class),
                        executions, authenticator))
                .setControllerAdvice(new TestExecutionProblemHandler())
                .build();
    }

    private static String request() {
        return """
                {"schemaVersion":"bloge.testMutationSuiteExecutionRequest.v1",
                 "suiteRef":{"suiteId":"mutation-suite","revision":2,
                 "fingerprint":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                 "clientRequestId":"mutation-ci-42","strategy":"STOP_AFTER_KILL",
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
