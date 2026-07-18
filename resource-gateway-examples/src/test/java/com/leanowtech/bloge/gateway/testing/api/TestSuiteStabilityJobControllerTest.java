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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TestSuiteStabilityJobControllerTest {

    private static final String JOB_ID = "stability-job-" + "a".repeat(64);

    @Test
    void submissionAuthenticatesDedicatedOperationAndReturnsAcceptedPayloadFreeLocation()
            throws Exception {
        TestSuiteStabilityJobService service = mock(TestSuiteStabilityJobService.class);
        when(service.submit(eq("suite-a"), any(), any())).thenReturn(
                new TestSuiteStabilityJobSubmitResponse("", view(), false));
        RecordingAudit audit = new RecordingAudit();
        MockMvc mvc = mvc(service, audit, Set.of("TEST_EXECUTION"));

        mvc.perform(post("/api/testing/suites/suite-a/stability-jobs")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .header("X-Correlation-Id", "correlation-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson()))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location",
                        "/api/testing/stability-jobs/" + JOB_ID))
                .andExpect(jsonPath("$.schemaVersion")
                        .value(TestSuiteStabilityJobSubmitResponse.SCHEMA_VERSION))
                .andExpect(jsonPath("$.job.schemaVersion")
                        .value(TestSuiteStabilityJobView.SCHEMA_VERSION))
                .andExpect(jsonPath("$.job.status").value("QUEUED"))
                .andExpect(jsonPath("$.job.terminal").value(false))
                .andExpect(jsonPath("$.job.principal").doesNotExist())
                .andExpect(jsonPath("$.job.execution").doesNotExist())
                .andExpect(jsonPath("$.job.metadata").doesNotExist())
                .andExpect(jsonPath("$.job.leaseOwner").doesNotExist())
                .andExpect(jsonPath("$.job.recordFingerprint").doesNotExist())
                .andExpect(jsonPath("$.job.stabilityRunId").doesNotExist());

        verify(service).submit(eq("suite-a"),
                org.mockito.ArgumentMatchers.argThat(request ->
                        request.schemaVersion().equals(
                                TestSuiteStabilityJobSubmitRequest.SCHEMA_VERSION)
                                && request.execution().clientRequestId()
                                .equals("stability-request-1")
                                && request.priority()
                                == TestSuiteStabilityJobSubmission.Priority.HIGH),
                org.mockito.ArgumentMatchers.argThat(identity ->
                        identity.tenantId().equals("tenant-a")
                                && identity.correlationId().equals("correlation-a")));
        assertThat(audit.records).singleElement().satisfies(record ->
                assertThat(record.operation()).isEqualTo(
                        "TEST_SUITE_STABILITY_JOB_SUBMIT"));
    }

    @Test
    void queryAndCancellationUseDistinctLeastPrivilegeOperations() throws Exception {
        TestSuiteStabilityJobService service = mock(TestSuiteStabilityJobService.class);
        when(service.find(eq(JOB_ID), any())).thenReturn(view());
        when(service.cancel(eq(JOB_ID), any(), any())).thenReturn(view());
        RecordingAudit audit = new RecordingAudit();
        MockMvc mvc = mvc(service, audit, Set.of("TEST_REPLAY"));

        mvc.perform(get("/api/testing/stability-jobs/{jobId}", JOB_ID)
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_REPLAY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(JOB_ID));
        mvc.perform(post("/api/testing/stability-jobs/{jobId}/cancellations", JOB_ID)
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_REPLAY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"schemaVersion":"%s","clientRequestId":"cancel-1"}
                                """.formatted(TestSuiteStabilityJobCancelRequest.SCHEMA_VERSION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(JOB_ID));

        verify(service).find(eq(JOB_ID),
                org.mockito.ArgumentMatchers.argThat(identity ->
                        identity.purpose().equals("TEST_REPLAY")));
        verify(service).cancel(eq(JOB_ID),
                org.mockito.ArgumentMatchers.argThat(request ->
                        request.clientRequestId().equals("cancel-1")),
                org.mockito.ArgumentMatchers.argThat(identity ->
                        identity.purpose().equals("TEST_REPLAY")));
        assertThat(audit.records).extracting(IntegrationAccessAuditRecord::operation)
                .containsExactly("TEST_SUITE_STABILITY_JOB_READ",
                        "TEST_SUITE_STABILITY_JOB_CANCEL");
    }

    @Test
    void authenticationAndJsonDecodingFailBeforeApplicationService() throws Exception {
        TestSuiteStabilityJobService service = mock(TestSuiteStabilityJobService.class);
        RecordingAudit audit = new RecordingAudit();
        MockMvc mvc = mvc(service, audit, Set.of("TEST_EXECUTION"));

        mvc.perform(post("/api/testing/suites/suite-a/stability-jobs")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code")
                        .value("RG.INTEGRATION.AUTHENTICATION_REQUIRED"));
        mvc.perform(post("/api/testing/suites/suite-a/stability-jobs")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_FIXTURE_READ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code")
                        .value("RG.INTEGRATION.PURPOSE_FORBIDDEN"));
        mvc.perform(post("/api/testing/suites/suite-a/stability-jobs")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "TEST_EXECUTION")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RG.TEST.REQUEST_MALFORMED"));

        verifyNoInteractions(service);
    }

    private static String submitJson() {
        return """
                {
                  "schemaVersion":"%s",
                  "execution":{
                    "schemaVersion":"%s",
                    "suiteRef":{"suiteId":"suite-a","revision":7,"fingerprint":"%s"},
                    "clientRequestId":"stability-request-1",
                    "attempts":3,
                    "metadata":{"pipeline":"nightly"}
                  },
                  "priority":"HIGH",
                  "deadlineAt":"2026-07-19T00:00:00Z"
                }
                """.formatted(TestSuiteStabilityJobSubmitRequest.SCHEMA_VERSION,
                TestSuiteStabilityExecutionRequest.SCHEMA_VERSION_V1, sha('a'));
    }

    private static TestSuiteStabilityJobView view() {
        Instant createdAt = Instant.parse("2026-07-18T23:00:00Z");
        return new TestSuiteStabilityJobView("", JOB_ID,
                new TestSuiteExecutionRequest.SuiteRef("suite-a", 7, sha('a')),
                "stability-request-1", sha('b'),
                TestSuiteStabilityJobSubmission.Priority.HIGH,
                TestSuiteStabilityJobRecord.Status.QUEUED, 0, createdAt,
                Instant.parse("2026-07-19T00:00:00Z"), createdAt, createdAt,
                Instant.parse("2026-08-18T00:00:00Z"), false,
                "", "", "");
    }

    private static MockMvc mvc(
            TestSuiteStabilityJobService service,
            RecordingAudit audit,
            Set<String> purposes) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "test-runtime", "tenant-a", "org-a", "project-a", "test", "local",
                "WORKLOAD", "runner", "", purposes, Instant.MAX, true,
                Set.of("quality"), "CONFIDENTIAL", "", Instant.MAX);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("test-token", identity, false), audit);
        return MockMvcBuilders.standaloneSetup(
                        new TestSuiteStabilityJobController(service, authenticator))
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
