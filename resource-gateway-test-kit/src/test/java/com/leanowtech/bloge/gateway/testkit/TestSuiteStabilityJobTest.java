package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteStabilityJobTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SUITE_FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final String JOB_ID = "stability-job-" + "b".repeat(64);
    private static final Instant DEADLINE = Instant.parse("2026-07-19T00:00:00Z");

    @Test
    void buildsStrictFixedAndStatisticalSubmissionRequests() {
        TestSuiteStabilityJobRequest fixed = request("request-1");
        JsonNode fixedWire = fixed.rawRequest();

        assertThat(fixedWire.path("schemaVersion").asText())
                .isEqualTo(TestingProtocol.TEST_SUITE_STABILITY_JOB_SUBMIT_REQUEST_V1);
        assertThat(fixedWire.at("/execution/schemaVersion").asText())
                .isEqualTo(TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_REQUEST_V1);
        assertThat(fixedWire.at("/execution/attempts").asInt()).isEqualTo(3);
        assertThat(fixedWire.at("/execution/statisticalPolicy").isMissingNode()).isTrue();
        assertThat(fixed.executionFingerprint())
                .isEqualTo(EvidenceVerificationSupport.sha256(fixedWire.path("execution")));

        TestSuiteStabilityStatisticalPolicy policy =
                TestSuiteStabilityStatisticalPolicy.exactBinomial(5_000, 5_000);
        TestSuiteStabilityJobRequest statistical = TestSuiteStabilityJobRequest.statistical(
                "suite-a", 7, SUITE_FINGERPRINT, "request-2",
                policy.minimumRequiredAttempts(), policy, Map.of("source", "nightly"),
                TestSuiteStabilityJobRequest.Priority.HIGH, DEADLINE);

        assertThat(statistical.rawRequest().at("/execution/schemaVersion").asText())
                .isEqualTo(TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_REQUEST_V2);
        assertThat(statistical.rawRequest().at(
                "/execution/statisticalPolicy/stoppingRule").asText())
                .isEqualTo("PRECOMMITTED_FIXED_HORIZON");

        TestSuiteStabilityStatisticalPolicy sequentialPolicy =
                TestSuiteStabilityStatisticalPolicy.anytimeValidEProcess(9_500, 1_000, 500);
        TestSuiteStabilityJobRequest sequential = TestSuiteStabilityJobRequest.statistical(
                "suite-a", 7, SUITE_FINGERPRINT, "request-3", 100,
                sequentialPolicy, Map.of("source", "nightly"),
                TestSuiteStabilityJobRequest.Priority.HIGH, DEADLINE);
        assertThat(sequential.rawRequest().at("/execution/schemaVersion").asText())
                .isEqualTo(TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_REQUEST_V4);
        assertThat(sequential.rawRequest().at(
                "/execution/statisticalPolicy/stoppingRule").asText())
                .isEqualTo("ANYTIME_VALID_E_PROCESS");
        assertThat(sequential.rawRequest().at(
                "/execution/statisticalPolicy/alternativeInstabilityRateBps").asInt())
                .isEqualTo(500);
        ((ObjectNode) fixedWire.path("execution")).put("clientRequestId", "mutated");
        assertThat(fixed.rawRequest().at("/execution/clientRequestId").asText())
                .isEqualTo("request-1");
    }

    @Test
    void rejectsInvalidRequestAndPolicyBoundsBeforeTransport() {
        assertThatThrownBy(() -> TestSuiteStabilityJobRequest.fixedHorizon(
                "suite-a", 7, SUITE_FINGERPRINT, "request with spaces", 3,
                Map.of(), TestSuiteStabilityJobRequest.Priority.NORMAL, DEADLINE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestSuiteStabilityJobRequest.fixedHorizon(
                "suite-a", 7, SUITE_FINGERPRINT, "request-1", 3,
                Map.of("bad key", "private-value"),
                TestSuiteStabilityJobRequest.Priority.NORMAL, DEADLINE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("private-value");
        assertThatThrownBy(() -> TestSuiteStabilityJobRequest.fixedHorizon(
                "suite-a", 7, SUITE_FINGERPRINT, "request-1", 3,
                Map.of("source", new CyclicMetadata()),
                TestSuiteStabilityJobRequest.Priority.NORMAL, DEADLINE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("private-metadata");
        assertThatThrownBy(() -> new TestSuiteStabilityJobRetryPolicy(
                0, Duration.ofMillis(1), Duration.ofSeconds(1), Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestSuiteStabilityJobPollingPolicy(
                2, Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestSuiteStabilityJobPollingPolicy(
                2, Duration.ofSeconds(1), Duration.ofNanos(1), Duration.ofMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void projectsPayloadFreeLifecycleAndDefensivelyCopiesRawResponse() {
        TestSuiteStabilityJobRequest request = request("request-1");
        ObjectNode response = view(request, TestSuiteStabilityJob.Status.QUEUED);

        TestSuiteStabilityJob job = TestSuiteStabilityJob.from(response);

        assertThat(job.jobId()).isEqualTo(JOB_ID);
        assertThat(job.terminal()).isFalse();
        assertThat(job.requestFingerprint()).isEqualTo(request.executionFingerprint());
        job.requireSubmission(request);
        ((ObjectNode) job.rawResponse()).put("status", "FAILED");
        assertThat(job.rawResponse().path("status").asText()).isEqualTo("QUEUED");
    }

    @Test
    void enforcesTerminalReferencesAndRejectsSensitiveExtensions() {
        TestSuiteStabilityJobRequest request = request("request-1");
        ObjectNode succeeded = view(request, TestSuiteStabilityJob.Status.SUCCEEDED);
        TestSuiteStabilityJob job = TestSuiteStabilityJob.from(succeeded);

        assertThat(job.terminal()).isTrue();
        assertThat(job.stabilityRunId()).matches("stability-[0-9a-f]{64}");

        ObjectNode missingEvidence = succeeded.deepCopy();
        missingEvidence.remove("evidenceFingerprint");
        assertThatThrownBy(() -> TestSuiteStabilityJob.from(missingEvidence))
                .isInstanceOf(IllegalArgumentException.class);

        ObjectNode sensitive = view(request, TestSuiteStabilityJob.Status.QUEUED);
        sensitive.putObject("principal").put("actorId", "private-actor");
        assertThatThrownBy(() -> TestSuiteStabilityJob.from(sensitive))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("private-actor");
    }

    @Test
    void submissionBindsEveryImmutableRequestCoordinate() {
        TestSuiteStabilityJobRequest request = request("request-1");
        ObjectNode response = JSON.createObjectNode();
        response.put("schemaVersion",
                TestingProtocol.TEST_SUITE_STABILITY_JOB_SUBMIT_RESPONSE_V1);
        response.set("job", view(request, TestSuiteStabilityJob.Status.QUEUED));
        response.put("idempotentReplay", true);
        TestSuiteStabilityJobSubmission submission =
                TestSuiteStabilityJobSubmission.from(response);

        submission.requireSubmission(request);
        assertThat(submission.idempotentReplay()).isTrue();
        assertThatThrownBy(() -> submission.requireSubmission(request("request-2")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static TestSuiteStabilityJobRequest request(String clientRequestId) {
        return TestSuiteStabilityJobRequest.fixedHorizon(
                "suite-a", 7, SUITE_FINGERPRINT, clientRequestId, 3,
                Map.of("source", "ci"), TestSuiteStabilityJobRequest.Priority.NORMAL,
                DEADLINE);
    }

    private static ObjectNode view(
            TestSuiteStabilityJobRequest request,
            TestSuiteStabilityJob.Status status) {
        ObjectNode view = JSON.createObjectNode();
        view.put("schemaVersion", TestingProtocol.TEST_SUITE_STABILITY_JOB_VIEW_V1);
        view.put("jobId", JOB_ID);
        ObjectNode suite = view.putObject("suiteRef");
        suite.put("suiteId", request.suiteId());
        suite.put("revision", request.revision());
        suite.put("fingerprint", request.fingerprint());
        view.put("clientRequestId", request.clientRequestId());
        view.put("requestFingerprint", request.executionFingerprint());
        view.put("priority", request.priority().name());
        view.put("status", status.name());
        view.put("retryCount", 0);
        view.put("nextEligibleAt", "2026-07-18T00:00:00Z");
        view.put("deadlineAt", request.deadlineAt().toString());
        view.put("createdAt", "2026-07-18T00:00:00Z");
        view.put("updatedAt", "2026-07-18T00:00:01Z");
        view.put("expiresAt", "2026-08-18T00:00:00Z");
        view.put("terminal", status.terminal());
        if (status == TestSuiteStabilityJob.Status.SUCCEEDED) {
            view.put("stabilityRunId", "stability-" + "c".repeat(64));
            view.put("evidenceFingerprint", "sha256:" + "d".repeat(64));
        }
        return view;
    }

    private static final class CyclicMetadata {
        public final String secret = "private-metadata";
        public final CyclicMetadata self = this;
    }
}
