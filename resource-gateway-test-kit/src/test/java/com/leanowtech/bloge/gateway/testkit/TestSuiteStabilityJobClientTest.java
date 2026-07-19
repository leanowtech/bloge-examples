package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteStabilityJobClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SUITE_FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final String JOB_ID = "stability-job-" + "b".repeat(64);
    private static final Instant DEADLINE = Instant.parse("2026-07-19T00:00:00Z");

    private final List<CapturedRequest> requests = new ArrayList<>();
    private final AtomicInteger submissionAttempts = new AtomicInteger();
    private final AtomicInteger queryAttempts = new AtomicInteger();
    private final AtomicInteger cancellationAttempts = new AtomicInteger();
    private HttpServer server;
    private Scenario scenario;
    private ObjectNode retainedExecution;
    private String retainedPriority;
    private String retainedDeadline;

    @BeforeEach
    void startServer() throws IOException {
        scenario = Scenario.NORMAL;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/testing", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void submitsQueriesAndCancelsWithStrictTypedProtocol() {
        ResourceGatewayTestClient client = client();
        TestSuiteStabilityJobRequest request = request("request-1");

        TestSuiteStabilityJobSubmission submitted =
                client.submitSuiteStabilityJob(request);
        TestSuiteStabilityJob found = client.findSuiteStabilityJob(JOB_ID);
        TestSuiteStabilityJob cancelled =
                client.cancelSuiteStabilityJob(JOB_ID, "cancel-1");

        assertThat(submitted.idempotentReplay()).isFalse();
        assertThat(submitted.job().status()).isEqualTo(TestSuiteStabilityJob.Status.QUEUED);
        assertThat(found.status()).isEqualTo(TestSuiteStabilityJob.Status.RUNNING);
        assertThat(cancelled.status()).isEqualTo(TestSuiteStabilityJob.Status.CANCELLED);
        assertThat(requests).extracting(CapturedRequest::method)
                .containsExactly("POST", "GET", "POST");
        assertThat(requests).extracting(CapturedRequest::purpose)
                .containsOnly("TEST_EXECUTION");
        assertThat(requests.get(0).path()).isEqualTo(
                "/api/testing/suites/suite-a/stability-jobs");
        assertThat(requests.get(0).body().path("schemaVersion").asText())
                .isEqualTo(TestingProtocol.TEST_SUITE_STABILITY_JOB_SUBMIT_REQUEST_V1);
        assertThat(requests.get(2).body().path("schemaVersion").asText())
                .isEqualTo(TestingProtocol.TEST_SUITE_STABILITY_JOB_CANCEL_REQUEST_V1);
        assertThat(requests).allSatisfy(captured -> {
            assertThat(captured.authorization()).isEqualTo("Bearer secret-token");
            assertThat(captured.correlationId()).isNotBlank();
        });
    }

    @Test
    void retriesStatisticalSubmissionWithSameIntentInsideBothBounds() {
        scenario = Scenario.RETRY_SUBMISSION_ONCE;
        TestSuiteStabilityStatisticalPolicy statisticalPolicy =
                TestSuiteStabilityStatisticalPolicy.exactBinomial(5_000, 5_000);
        TestSuiteStabilityJobRequest request = TestSuiteStabilityJobRequest.statistical(
                "suite-a", 7, SUITE_FINGERPRINT, "statistical-1",
                statisticalPolicy.minimumRequiredAttempts(), statisticalPolicy,
                Map.of("source", "nightly"), TestSuiteStabilityJobRequest.Priority.HIGH,
                DEADLINE);
        TestSuiteStabilityJobRetryPolicy retryPolicy =
                new TestSuiteStabilityJobRetryPolicy(
                        3, Duration.ofMillis(1), Duration.ofMillis(10), Duration.ofSeconds(1));

        TestSuiteStabilityJobSubmission submitted =
                client().submitSuiteStabilityJob(request, retryPolicy);

        assertThat(submitted.job().priority())
                .isEqualTo(TestSuiteStabilityJobRequest.Priority.HIGH);
        assertThat(submissionAttempts).hasValue(2);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).body()).isEqualTo(requests.get(1).body());
        assertThat(requests.get(1).body().at("/execution/statisticalPolicy/model").asText())
                .isEqualTo("ZERO_INSTABILITY_EXACT_BINOMIAL");
    }

    @Test
    void submitsBaselineConditionalJobsUnderRequestV3() {
        TestSuiteStabilityStatisticalPolicy policy =
                TestSuiteStabilityStatisticalPolicy.baselineConditionalExactBinomial(
                        9_500, 1_000);
        TestSuiteStabilityJobRequest request = TestSuiteStabilityJobRequest.statistical(
                "suite-a", 7, SUITE_FINGERPRINT, "rate-1",
                policy.minimumRequiredAttempts(), policy,
                Map.of("source", "nightly"), TestSuiteStabilityJobRequest.Priority.NORMAL,
                DEADLINE);

        client().submitSuiteStabilityJob(request);

        assertThat(requests.getFirst().body().at("/execution/schemaVersion").asText())
                .isEqualTo(TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_REQUEST_V3);
        assertThat(requests.getFirst().body()
                .at("/execution/statisticalPolicy/model").asText())
                .isEqualTo("BASELINE_CONDITIONAL_EXACT_BINOMIAL");
        assertThat(requests.getFirst().body().at("/execution/attempts").asInt())
                .isEqualTo(30);
    }

    @Test
    void retriesCancellationWithTheSameCommandIdentity() {
        scenario = Scenario.RETRY_CANCELLATION_ONCE;
        ResourceGatewayTestClient client = client();
        client.submitSuiteStabilityJob(request("request-1"));
        TestSuiteStabilityJobRetryPolicy retryPolicy =
                new TestSuiteStabilityJobRetryPolicy(
                        3, Duration.ofMillis(1), Duration.ofMillis(10), Duration.ofSeconds(1));

        TestSuiteStabilityJob cancelled = client.cancelSuiteStabilityJob(
                JOB_ID, "cancel-1", retryPolicy);

        assertThat(cancelled.status()).isEqualTo(TestSuiteStabilityJob.Status.CANCELLED);
        assertThat(cancellationAttempts).hasValue(2);
        assertThat(requests.get(1).body()).isEqualTo(requests.get(2).body());
    }

    @Test
    void stopsRetryAtAttemptBoundAndPreservesSafeServerFailure() {
        scenario = Scenario.ALWAYS_UNAVAILABLE;
        TestSuiteStabilityJobRetryPolicy retryPolicy =
                new TestSuiteStabilityJobRetryPolicy(
                        2, Duration.ofMillis(1), Duration.ofMillis(10), Duration.ofSeconds(1));

        assertThatThrownBy(() -> client().submitSuiteStabilityJob(
                request("request-1"), retryPolicy))
                .isInstanceOfSatisfying(ResourceGatewayTestException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(503);
                    assertThat(failure.code())
                            .isEqualTo("RG.TEST.STABILITY_JOB_SUBMISSION_UNAVAILABLE");
                    assertThat(failure.retryAfter()).contains(Duration.ZERO);
                    assertThat(failure.getMessage()).doesNotContain("private-capacity-detail");
                });
        assertThat(submissionAttempts).hasValue(2);
    }

    @Test
    void refusesToRetryBeforeInvalidServerDirective() {
        scenario = Scenario.INVALID_RETRY_AFTER;
        TestSuiteStabilityJobRetryPolicy retryPolicy =
                new TestSuiteStabilityJobRetryPolicy(
                        3, Duration.ofMillis(1), Duration.ofMillis(10), Duration.ofSeconds(1));

        assertThatThrownBy(() -> client().submitSuiteStabilityJob(
                request("request-1"), retryPolicy))
                .isInstanceOfSatisfying(ResourceGatewayTestException.class, failure -> {
                    assertThat(failure.retryAfterSpecified()).isTrue();
                    assertThat(failure.retryAfter()).isEmpty();
                });
        assertThat(submissionAttempts).hasValue(1);
    }

    @Test
    void pollsUntilSuccessfulTerminalLifecycleWithinDualBounds() {
        scenario = Scenario.POLL_TO_SUCCESS;
        ResourceGatewayTestClient client = client();
        client.submitSuiteStabilityJob(request("request-1"));
        TestSuiteStabilityJobPollingPolicy pollingPolicy =
                new TestSuiteStabilityJobPollingPolicy(
                        3, Duration.ofSeconds(1), Duration.ofMillis(1), Duration.ofMillis(10));

        TestSuiteStabilityJob terminal =
                client.awaitSuiteStabilityJob(JOB_ID, pollingPolicy);

        assertThat(terminal.status()).isEqualTo(TestSuiteStabilityJob.Status.SUCCEEDED);
        assertThat(terminal.terminal()).isTrue();
        assertThat(queryAttempts).hasValue(3);
    }

    @Test
    void returnsFailedTerminalLifecycleForCallerPolicy() {
        scenario = Scenario.TERMINAL_FAILURE;
        ResourceGatewayTestClient client = client();
        client.submitSuiteStabilityJob(request("request-1"));

        TestSuiteStabilityJob terminal = client.awaitSuiteStabilityJob(
                JOB_ID, new TestSuiteStabilityJobPollingPolicy(
                        1, Duration.ofSeconds(1), Duration.ofMillis(1), Duration.ofMillis(10)));

        assertThat(terminal.status()).isEqualTo(TestSuiteStabilityJob.Status.FAILED);
        assertThat(terminal.failureCode()).isEqualTo("RG.TEST.STABILITY_JOB_ATTEMPT_FAILED");
    }

    @Test
    void exposesBoundedRetryAfterWithoutProblemDetails() {
        scenario = Scenario.QUERY_UNAVAILABLE;

        assertThatThrownBy(() -> client().findSuiteStabilityJob(JOB_ID))
                .isInstanceOfSatisfying(ResourceGatewayTestException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(503);
                    assertThat(failure.retryAfter()).contains(Duration.ofSeconds(2));
                    assertThat(failure.getMessage()).doesNotContain("private-store-detail");
                });
    }

    @Test
    void rejectsMismatchedSubmissionIntentAndCanonicalLocation() {
        scenario = Scenario.MISMATCHED_SUBMISSION;
        assertThatThrownBy(() -> client().submitSuiteStabilityJob(request("request-1")))
                .isInstanceOfSatisfying(ResourceGatewayTestException.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo("RG.TESTKIT.RESPONSE_CONTRACT_INVALID"));

        requests.clear();
        scenario = Scenario.BAD_LOCATION;
        assertThatThrownBy(() -> client().submitSuiteStabilityJob(request("request-2")))
                .isInstanceOfSatisfying(ResourceGatewayTestException.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo("RG.TESTKIT.RESPONSE_CONTRACT_INVALID"));
    }

    @Test
    void rejectsSensitiveOrCrossResourceJobProjection() {
        scenario = Scenario.SENSITIVE_PROJECTION;
        assertThatThrownBy(() -> client().findSuiteStabilityJob(JOB_ID))
                .isInstanceOfSatisfying(ResourceGatewayTestException.class, failure -> {
                    assertThat(failure.code())
                            .isEqualTo("RG.TESTKIT.RESPONSE_CONTRACT_INVALID");
                    assertThat(failure.getMessage()).doesNotContain("private-actor");
                });

        scenario = Scenario.MISMATCHED_QUERY;
        assertThatThrownBy(() -> client().findSuiteStabilityJob(JOB_ID))
                .isInstanceOfSatisfying(ResourceGatewayTestException.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo("RG.TESTKIT.RESPONSE_CONTRACT_INVALID"));
    }

    @Test
    void rejectsInvalidLocalIdentifiersBeforeNetworkIo() {
        assertThatThrownBy(() -> client().findSuiteStabilityJob("job-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client().cancelSuiteStabilityJob(
                JOB_ID, "cancel id with spaces"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).isEmpty();
    }

    private ResourceGatewayTestClient client() {
        return ResourceGatewayTestClient.builder(baseUri())
                .bearerToken(() -> "secret-token")
                .requestTimeout(Duration.ofSeconds(2))
                .build();
    }

    private URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static TestSuiteStabilityJobRequest request(String clientRequestId) {
        return TestSuiteStabilityJobRequest.fixedHorizon(
                "suite-a", 7, SUITE_FINGERPRINT, clientRequestId, 3,
                Map.of("source", "ci"), TestSuiteStabilityJobRequest.Priority.NORMAL,
                DEADLINE);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        JsonNode body = bytes.length == 0 ? JSON.nullNode() : JSON.readTree(bytes);
        requests.add(new CapturedRequest(
                exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                exchange.getRequestHeaders().getFirst("X-Purpose"),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("X-Correlation-Id"), body));
        String path = exchange.getRequestURI().getRawPath();
        if ("POST".equals(exchange.getRequestMethod())
                && path.endsWith("/suites/suite-a/stability-jobs")) {
            handleSubmission(exchange, body);
        } else if ("POST".equals(exchange.getRequestMethod())
                && path.endsWith("/cancellations")) {
            int attempt = cancellationAttempts.incrementAndGet();
            if (scenario == Scenario.RETRY_CANCELLATION_ONCE && attempt == 1) {
                unavailable(exchange, "0", "private-cancellation-detail");
            } else {
                respond(exchange, 200, jobView(TestSuiteStabilityJob.Status.CANCELLED));
            }
        } else if ("GET".equals(exchange.getRequestMethod())
                && path.contains("/stability-jobs/")) {
            handleQuery(exchange);
        } else {
            respond(exchange, 404, JSON.createObjectNode());
        }
    }

    private void handleSubmission(HttpExchange exchange, JsonNode body) throws IOException {
        int attempt = submissionAttempts.incrementAndGet();
        if (scenario == Scenario.INVALID_RETRY_AFTER) {
            unavailable(exchange, "999999", "private-capacity-detail");
            return;
        }
        if (scenario == Scenario.ALWAYS_UNAVAILABLE
                || scenario == Scenario.RETRY_SUBMISSION_ONCE && attempt == 1) {
            unavailable(exchange, "0", "private-capacity-detail");
            return;
        }
        retainedExecution = ((ObjectNode) body.path("execution")).deepCopy();
        retainedPriority = body.path("priority").asText();
        retainedDeadline = body.path("deadlineAt").asText();
        ObjectNode job = jobView(TestSuiteStabilityJob.Status.QUEUED);
        if (scenario == Scenario.MISMATCHED_SUBMISSION) {
            job.put("requestFingerprint", "sha256:" + "f".repeat(64));
        }
        ObjectNode response = JSON.createObjectNode();
        response.put("schemaVersion",
                TestingProtocol.TEST_SUITE_STABILITY_JOB_SUBMIT_RESPONSE_V1);
        response.set("job", job);
        response.put("idempotentReplay", false);
        exchange.getResponseHeaders().set("Location", scenario == Scenario.BAD_LOCATION
                ? "/api/testing/stability-jobs/wrong" : "/api/testing/stability-jobs/" + JOB_ID);
        respond(exchange, 202, response);
    }

    private void handleQuery(HttpExchange exchange) throws IOException {
        int attempt = queryAttempts.incrementAndGet();
        if (scenario == Scenario.QUERY_UNAVAILABLE) {
            unavailable(exchange, "2", "private-store-detail");
            return;
        }
        TestSuiteStabilityJob.Status status = switch (scenario) {
            case POLL_TO_SUCCESS -> attempt == 1 ? TestSuiteStabilityJob.Status.QUEUED
                    : attempt == 2 ? TestSuiteStabilityJob.Status.RUNNING
                    : TestSuiteStabilityJob.Status.SUCCEEDED;
            case TERMINAL_FAILURE -> TestSuiteStabilityJob.Status.FAILED;
            default -> TestSuiteStabilityJob.Status.RUNNING;
        };
        ObjectNode response = jobView(status);
        if (scenario == Scenario.SENSITIVE_PROJECTION) {
            response.putObject("principal").put("actorId", "private-actor");
        } else if (scenario == Scenario.MISMATCHED_QUERY) {
            response.put("jobId", "stability-job-" + "e".repeat(64));
        }
        respond(exchange, 200, response);
    }

    private ObjectNode jobView(TestSuiteStabilityJob.Status status) {
        ObjectNode execution = retainedExecution == null
                ? (ObjectNode) request("request-1").rawRequest().path("execution")
                : retainedExecution;
        ObjectNode view = JSON.createObjectNode();
        view.put("schemaVersion", TestingProtocol.TEST_SUITE_STABILITY_JOB_VIEW_V1);
        view.put("jobId", JOB_ID);
        view.set("suiteRef", execution.path("suiteRef").deepCopy());
        view.put("clientRequestId", execution.path("clientRequestId").asText());
        view.put("requestFingerprint", EvidenceVerificationSupport.sha256(execution));
        view.put("priority", retainedPriority == null ? "NORMAL" : retainedPriority);
        view.put("status", status.name());
        view.put("retryCount", 0);
        view.put("nextEligibleAt", "2026-07-18T00:00:00Z");
        view.put("deadlineAt", retainedDeadline == null ? DEADLINE.toString() : retainedDeadline);
        view.put("createdAt", "2026-07-18T00:00:00Z");
        view.put("updatedAt", "2026-07-18T00:00:01Z");
        view.put("expiresAt", "2026-08-18T00:00:00Z");
        view.put("terminal", status.terminal());
        if (status == TestSuiteStabilityJob.Status.SUCCEEDED) {
            view.put("stabilityRunId", "stability-" + "c".repeat(64));
            view.put("evidenceFingerprint", "sha256:" + "d".repeat(64));
        } else if (status == TestSuiteStabilityJob.Status.FAILED) {
            view.put("failureCode", "RG.TEST.STABILITY_JOB_ATTEMPT_FAILED");
        } else if (status == TestSuiteStabilityJob.Status.CANCELLED) {
            view.put("failureCode", "RG.TEST.STABILITY_JOB_CANCELLED");
        }
        return view;
    }

    private static void unavailable(
            HttpExchange exchange,
            String retryAfter,
            String privateDetail) throws IOException {
        exchange.getResponseHeaders().set("Retry-After", retryAfter);
        ObjectNode problem = JSON.createObjectNode();
        problem.put("schemaVersion", "toolStudio.resourceGateway.problem.v1");
        problem.put("title", "Stability work is temporarily unavailable.");
        problem.put("status", 503);
        problem.put("code", "RG.TEST.STABILITY_JOB_SUBMISSION_UNAVAILABLE");
        problem.put("retryable", true);
        problem.put("correlationId", "server-correlation");
        problem.putObject("details").put("private", privateDetail);
        respond(exchange, 503, problem);
    }

    private static void respond(
            HttpExchange exchange,
            int status,
            JsonNode body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private enum Scenario {
        NORMAL,
        RETRY_SUBMISSION_ONCE,
        RETRY_CANCELLATION_ONCE,
        ALWAYS_UNAVAILABLE,
        INVALID_RETRY_AFTER,
        POLL_TO_SUCCESS,
        TERMINAL_FAILURE,
        QUERY_UNAVAILABLE,
        MISMATCHED_SUBMISSION,
        BAD_LOCATION,
        SENSITIVE_PROJECTION,
        MISMATCHED_QUERY
    }

    private record CapturedRequest(
            String method,
            String path,
            String purpose,
            String authorization,
            String correlationId,
            JsonNode body) {
    }
}
