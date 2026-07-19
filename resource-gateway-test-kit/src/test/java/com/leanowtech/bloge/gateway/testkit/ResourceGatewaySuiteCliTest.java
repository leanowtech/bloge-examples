package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceGatewaySuiteCliTest {

    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private volatile String purpose = "";
    private volatile String authorization = "";
    private volatile String requestPath = "";
    private volatile String requestBody = "";
    private TestSuiteStabilityTestFixtures.Fixture stabilityFixture;

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void startServer() throws IOException {
        stabilityFixture = TestSuiteStabilityTestFixtures.fixture();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/testing/suites/loan-policy/executions", this::executeSuite);
        server.createContext("/api/testing/suites/blocked-policy/executions", this::executeBlockedSuite);
        server.createContext("/api/testing/suites/running-policy/executions", this::executeRunningSuite);
        server.createContext("/api/testing/suites/suite-boundary/executions", this::executeAdmissionSuite);
        server.createContext("/api/testing/suites/suite-mutation/mutation-executions",
                this::executeMutationSuite);
        server.createContext("/api/testing/suites/orders-suite/stability-executions",
                this::executeStability);
        server.createContext("/api/integration/evidence-keys", this::findEvidenceKeys);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void runsExactSuiteWritesJUnitAndReturnsZeroOnlyForEligibleEvidence() throws Exception {
        Path report = temporaryDirectory.resolve("ci/resource-gateway.xml");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        String token = "ci-secret-token";

        int exit = ResourceGatewaySuiteCli.run(new String[]{
                        "--base-uri", "http://127.0.0.1:" + server.getAddress().getPort(),
                        "--suite-id", "loan-policy",
                        "--revision", "3",
                        "--fingerprint", "sha256:" + "a".repeat(64),
                        "--client-request-id", "pipeline-982-job-4",
                        "--report", report.toString(),
                }, Map.of("RESOURCE_GATEWAY_TOKEN", token,
                        "RESOURCE_GATEWAY_STABILITY_ATTEMPTS", "not-used-in-standard-mode"),
                new PrintStream(output), new PrintStream(error));

        assertThat(exit).isZero();
        assertThat(purpose).isEqualTo("TEST_EXECUTION");
        assertThat(authorization).isEqualTo("Bearer " + token);
        assertThat(Files.readString(report))
                .contains("tests=\"2\"")
                .contains("failures=\"0\"")
                .contains("case-golden")
                .contains("suite-gate");
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("suiteRunId=suite-run-982")
                .contains("status=PASSED")
                .contains("promotion=ELIGIBLE")
                .doesNotContain(token);
        assertThat(error.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void failsClosedBeforeNetworkWhenRequiredIdentityIsMissing() {
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exit = ResourceGatewaySuiteCli.run(new String[]{
                        "--base-uri", "http://127.0.0.1:" + server.getAddress().getPort(),
                        "--suite-id", "loan-policy",
                        "--revision", "3",
                        "--fingerprint", "sha256:" + "a".repeat(64),
                }, Map.of(), new PrintStream(new ByteArrayOutputStream()), new PrintStream(error));

        assertThat(exit).isEqualTo(2);
        assertThat(error.toString(StandardCharsets.UTF_8))
                .contains("client-request-id")
                .contains("RESOURCE_GATEWAY_TOKEN")
                .doesNotContain("Bearer");
        assertThat(purpose).isEmpty();
    }

    @Test
    void returnsOneAndWritesFailingGateWhenPromotionIsBlocked() throws Exception {
        Path report = temporaryDirectory.resolve("ci/blocked.xml");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = ResourceGatewaySuiteCli.run(new String[]{
                        "--base-uri", "http://127.0.0.1:" + server.getAddress().getPort(),
                        "--suite-id", "blocked-policy",
                        "--revision", "3",
                        "--fingerprint", "sha256:" + "a".repeat(64),
                        "--client-request-id", "pipeline-983-job-4",
                        "--report", report.toString(),
                }, Map.of("RESOURCE_GATEWAY_TOKEN", "ci-secret-token"),
                new PrintStream(output), new PrintStream(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("status=COMPLETED_WITH_FAILURES")
                .contains("promotion=BLOCKED");
        assertThat(Files.readString(report))
                .contains("failures=\"2\"")
                .contains("ASSERTION_FAILED")
                .contains("PROMOTION_BLOCKED")
                .contains("CASES_FAILED")
                .doesNotContain("private diagnostic");
    }

    @Test
    void treatsNonTerminalCheckpointAsInfrastructureOutcome() throws Exception {
        Path report = temporaryDirectory.resolve("ci/running.xml");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exit = ResourceGatewaySuiteCli.run(new String[]{
                        "--base-uri", "http://127.0.0.1:" + server.getAddress().getPort(),
                        "--suite-id", "running-policy",
                        "--revision", "3",
                        "--fingerprint", FINGERPRINT,
                        "--client-request-id", "pipeline-984-job-4",
                        "--report", report.toString(),
                }, Map.of("RESOURCE_GATEWAY_TOKEN", "ci-secret-token"),
                new PrintStream(output), new PrintStream(error));

        assertThat(exit).isEqualTo(2);
        assertThat(output.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(error.toString(StandardCharsets.UTF_8))
                .contains("RG.TESTKIT.SUITE_NON_TERMINAL")
                .doesNotContain("private");
        assertThat(Files.readString(report))
                .contains("failures=\"1\"")
                .contains("RG.TESTKIT.SUITE_NON_TERMINAL");
    }

    @Test
    void gatesSchemaAdmissionByAdmissionVerdictWhenPromotionIsExplicitlyOptional() throws Exception {
        Path report = temporaryDirectory.resolve("ci/schema-admission.xml");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exit = ResourceGatewaySuiteCli.run(new String[]{
                        "--base-uri", "http://127.0.0.1:" + server.getAddress().getPort(),
                        "--suite-id", "suite-boundary",
                        "--revision", "3",
                        "--fingerprint", FINGERPRINT,
                        "--client-request-id", "admission-ci-1",
                        "--report", report.toString(),
                        "--allow-non-eligible",
                }, Map.of("RESOURCE_GATEWAY_TOKEN", "ci-secret-token"),
                new PrintStream(output), new PrintStream(error));

        assertThat(exit).isZero();
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("evaluationMode=SCHEMA_ADMISSION")
                .contains("admissionCoverage=SATISFIED")
                .contains("promotion=BLOCKED");
        assertThat(error.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(Files.readString(report))
                .contains("tests=\"2\"")
                .contains("failures=\"0\"")
                .doesNotContain("child runId=");
    }

    @Test
    void runsMutationSuiteThroughDedicatedEndpointAndReportsEveryMutant() throws Exception {
        Path report = temporaryDirectory.resolve("ci/mutation.xml");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exit = ResourceGatewaySuiteCli.run(new String[]{
                        "--base-uri", "http://127.0.0.1:" + server.getAddress().getPort(),
                        "--suite-id", "suite-mutation",
                        "--revision", "5",
                        "--fingerprint", FINGERPRINT,
                        "--client-request-id", "mutation-ci-1",
                        "--mode", "MUTATION",
                        "--strategy", "STOP_AFTER_KILL",
                        "--report", report.toString(),
                }, Map.of("RESOURCE_GATEWAY_TOKEN", "ci-secret-token"),
                new PrintStream(output), new PrintStream(error));

        assertThat(exit).isZero();
        assertThat(purpose).isEqualTo("TEST_EXECUTION");
        assertThat(requestPath)
                .isEqualTo("/api/testing/suites/suite-mutation/mutation-executions");
        assertThat(requestBody)
                .contains("bloge.testMutationSuiteExecutionRequest.v1")
                .contains("\"strategy\":\"STOP_AFTER_KILL\"")
                .doesNotContain("ci-secret-token");
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("evaluationMode=PURE_DSL_MUTATION")
                .contains("mutationBaseline=PASSED")
                .contains("mutants=2")
                .contains("mutationScore=5000")
                .contains("mutationScoreStatus=SATISFIED");
        assertThat(error.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(Files.readString(report))
                .contains("tests=\"4\"")
                .contains("mutant-001")
                .contains("mutant-002")
                .contains("status=KILLED")
                .contains("status=SURVIVED")
                .contains("mutationScore=5000")
                .doesNotContain("/members/");
    }

    @Test
    void rejectsSuiteStrategyThatDoesNotBelongToSelectedModeBeforeNetwork() {
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exit = ResourceGatewaySuiteCli.run(new String[]{
                        "--base-uri", "http://127.0.0.1:" + server.getAddress().getPort(),
                        "--suite-id", "suite-mutation",
                        "--revision", "5",
                        "--fingerprint", FINGERPRINT,
                        "--client-request-id", "mutation-ci-1",
                        "--mode", "MUTATION",
                        "--strategy", "FAIL_FAST",
                }, Map.of("RESOURCE_GATEWAY_TOKEN", "ci-secret-token"),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(error));

        assertThat(exit).isEqualTo(2);
        assertThat(error.toString(StandardCharsets.UTF_8))
                .contains("strategy has an unsupported value")
                .doesNotContain("ci-secret-token");
        assertThat(purpose).isEmpty();
    }

    @Test
    void returnsOneWhenTerminalMutationEvidenceFailsItsFrozenScorePolicy() throws Exception {
        Path report = temporaryDirectory.resolve("ci/mutation-failed.xml");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exit = ResourceGatewaySuiteCli.run(new String[]{
                        "--base-uri", "http://127.0.0.1:" + server.getAddress().getPort(),
                        "--suite-id", "suite-mutation",
                        "--revision", "5",
                        "--fingerprint", FINGERPRINT,
                        "--client-request-id", "mutation-ci-failed",
                        "--mode", "MUTATION",
                        "--report", report.toString(),
                }, Map.of("RESOURCE_GATEWAY_TOKEN", "ci-secret-token"),
                new PrintStream(output), new PrintStream(error));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("status=COMPLETED_WITH_FAILURES")
                .contains("mutationScore=5000")
                .contains("mutationScoreStatus=UNSATISFIED");
        assertThat(error.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(Files.readString(report))
                .contains("failures=\"1\"")
                .contains("MUTATION_SCORE_UNSATISFIED")
                .contains("MUTATION_SCORE_BELOW_THRESHOLD")
                .doesNotContain("customer payload");
    }

    @Test
    void neverEchoesUnexpectedPositionalArgumentThatMayContainASecret() {
        String accidentalSecret = "accidental-secret-value";
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exit = ResourceGatewaySuiteCli.run(new String[]{accidentalSecret}, Map.of(),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(error));

        assertThat(exit).isEqualTo(2);
        assertThat(error.toString(StandardCharsets.UTF_8))
                .contains("Unexpected positional argument")
                .doesNotContain(accidentalSecret);
    }

    @Test
    void runsPinnedStabilityGateAndWritesPayloadFreeJUnit() throws Exception {
        Path report = temporaryDirectory.resolve("ci/stability.xml");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exit = ResourceGatewaySuiteCli.run(new String[]{
                        "--base-uri", "http://127.0.0.1:" + server.getAddress().getPort(),
                        "--suite-id", TestSuiteStabilityTestFixtures.SUITE_ID,
                        "--revision", Long.toString(TestSuiteStabilityTestFixtures.SUITE_REVISION),
                        "--fingerprint", TestSuiteStabilityTestFixtures.SUITE_FINGERPRINT,
                        "--client-request-id", TestSuiteStabilityTestFixtures.CLIENT_REQUEST_ID,
                        "--mode", "STABILITY",
                        "--attempts", "3",
                        "--trusted-key-set-fingerprint",
                        stabilityFixture.keySet().snapshotFingerprint(),
                        "--report", report.toString(),
                }, Map.of("RESOURCE_GATEWAY_TOKEN", "ci-secret-token"),
                new PrintStream(output), new PrintStream(error));

        assertThat(exit).isZero();
        assertThat(requestPath)
                .isEqualTo("/api/testing/suites/orders-suite/stability-executions");
        assertThat(requestBody).contains("\"attempts\":3").doesNotContain("ci-secret-token");
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("status=STABLE")
                .contains("promotion=ELIGIBLE")
                .contains("verification=VERIFIED")
                .doesNotContain("ci-secret-token");
        assertThat(error.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(Files.readString(report))
                .contains("tests=\"3\"")
                .contains("failures=\"0\"")
                .contains("stability-attestation")
                .contains("stability-gate")
                .doesNotContain("nightly");
    }

    @Test
    void runsAnExactStatisticalStabilityGateWithTheDerivedDefaultHorizon() throws Exception {
        Path report = temporaryDirectory.resolve("ci/statistical-stability.xml");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exit = ResourceGatewaySuiteCli.run(new String[]{
                        "--base-uri", "http://127.0.0.1:" + server.getAddress().getPort(),
                        "--suite-id", TestSuiteStabilityTestFixtures.SUITE_ID,
                        "--revision", Long.toString(TestSuiteStabilityTestFixtures.SUITE_REVISION),
                        "--fingerprint", TestSuiteStabilityTestFixtures.SUITE_FINGERPRINT,
                        "--client-request-id", TestSuiteStabilityTestFixtures.CLIENT_REQUEST_ID,
                        "--mode", "STABILITY",
                        "--confidence-bps", "9500",
                        "--max-instability-rate-bps", "1000",
                        "--trusted-key-set-fingerprint",
                        stabilityFixture.keySet().snapshotFingerprint(),
                        "--report", report.toString(),
                }, Map.of("RESOURCE_GATEWAY_TOKEN", "ci-secret-token"),
                new PrintStream(output), new PrintStream(error));

        assertThat(exit).isZero();
        assertThat(requestBody)
                .contains("bloge.testSuiteStabilityExecutionRequest.v3")
                .contains("\"attempts\":30")
                .contains("BASELINE_CONDITIONAL_EXACT_BINOMIAL")
                .contains("\"confidenceLevelBps\":9500")
                .contains("\"maximumInstabilityRateBps\":1000");
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("statisticalStatus=SATISFIED")
                .contains("requiredAttempts=30")
                .contains("comparisonAttempts=29")
                .contains("upperInstabilityRateBps=982")
                .doesNotContain("ci-secret-token");
        assertThat(error.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(Files.readString(report))
                .contains("statisticalModel=BASELINE_CONDITIONAL_EXACT_BINOMIAL")
                .contains("statisticalStatus=SATISFIED")
                .contains("requiredAttempts=30")
                .contains("comparisonAttempts=29")
                .contains("upperInstabilityRateBps=982");
    }

    @Test
    void rejectsPartialOrInsufficientStatisticalCliPoliciesBeforeNetwork() {
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        String[] partial = {
                "--base-uri", "http://127.0.0.1:" + server.getAddress().getPort(),
                "--suite-id", TestSuiteStabilityTestFixtures.SUITE_ID,
                "--revision", Long.toString(TestSuiteStabilityTestFixtures.SUITE_REVISION),
                "--fingerprint", TestSuiteStabilityTestFixtures.SUITE_FINGERPRINT,
                "--client-request-id", TestSuiteStabilityTestFixtures.CLIENT_REQUEST_ID,
                "--mode", "STABILITY", "--confidence-bps", "9500",
                "--trusted-key-set-fingerprint", stabilityFixture.keySet().snapshotFingerprint()
        };

        int partialExit = ResourceGatewaySuiteCli.run(partial,
                Map.of("RESOURCE_GATEWAY_TOKEN", "ci-secret-token"),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(error));

        assertThat(partialExit).isEqualTo(2);
        assertThat(error.toString(StandardCharsets.UTF_8))
                .contains("must be configured together");
        assertThat(requestPath).isEmpty();

        int insufficientExit = ResourceGatewaySuiteCli.run(new String[]{
                        "--base-uri", "http://127.0.0.1:" + server.getAddress().getPort(),
                        "--suite-id", TestSuiteStabilityTestFixtures.SUITE_ID,
                        "--revision", Long.toString(TestSuiteStabilityTestFixtures.SUITE_REVISION),
                        "--fingerprint", TestSuiteStabilityTestFixtures.SUITE_FINGERPRINT,
                        "--client-request-id", TestSuiteStabilityTestFixtures.CLIENT_REQUEST_ID,
                        "--mode", "STABILITY", "--attempts", "28",
                        "--confidence-bps", "9500",
                        "--max-instability-rate-bps", "1000",
                        "--trusted-key-set-fingerprint",
                        stabilityFixture.keySet().snapshotFingerprint(),
                }, Map.of("RESOURCE_GATEWAY_TOKEN", "ci-secret-token"),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(error));
        assertThat(insufficientExit).isEqualTo(2);
        assertThat(requestPath).isEmpty();
    }

    @Test
    void returnsOneForCryptographicallyVerifiedFlakyEvidence() throws Exception {
        Path report = temporaryDirectory.resolve("ci/stability-flaky.xml");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = ResourceGatewaySuiteCli.run(new String[]{
                        "--base-uri", "http://127.0.0.1:" + server.getAddress().getPort(),
                        "--suite-id", TestSuiteStabilityTestFixtures.SUITE_ID,
                        "--revision", Long.toString(TestSuiteStabilityTestFixtures.SUITE_REVISION),
                        "--fingerprint", TestSuiteStabilityTestFixtures.SUITE_FINGERPRINT,
                        "--client-request-id", "stability-flaky",
                        "--mode", "STABILITY",
                        "--trusted-key-set-fingerprint",
                        stabilityFixture.keySet().snapshotFingerprint(),
                        "--report", report.toString(),
                }, Map.of("RESOURCE_GATEWAY_TOKEN", "ci-secret-token"),
                new PrintStream(output), new PrintStream(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("status=FLAKY")
                .contains("quarantine=REQUIRED")
                .contains("verification=VERIFIED");
        assertThat(Files.readString(report))
                .contains("failures=\"2\"")
                .contains("ResourceGateway.FLAKY")
                .contains("STABILITY_GATE_BLOCKED")
                .doesNotContain("nightly");
    }

    @Test
    void stabilityModeRequiresPinnedTrustAndBoundedAttemptsBeforeNetwork() {
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        String[] base = {
                "--base-uri", "http://127.0.0.1:" + server.getAddress().getPort(),
                "--suite-id", TestSuiteStabilityTestFixtures.SUITE_ID,
                "--revision", Long.toString(TestSuiteStabilityTestFixtures.SUITE_REVISION),
                "--fingerprint", TestSuiteStabilityTestFixtures.SUITE_FINGERPRINT,
                "--client-request-id", TestSuiteStabilityTestFixtures.CLIENT_REQUEST_ID,
                "--mode", "STABILITY"
        };

        int missingPin = ResourceGatewaySuiteCli.run(base,
                Map.of("RESOURCE_GATEWAY_TOKEN", "ci-secret-token"),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(error));

        assertThat(missingPin).isEqualTo(2);
        assertThat(error.toString(StandardCharsets.UTF_8))
                .contains("trusted-key-set-fingerprint")
                .doesNotContain("ci-secret-token");
        assertThat(requestPath).isEmpty();

        int unbounded = ResourceGatewaySuiteCli.run(new String[]{
                        "--base-uri", "http://127.0.0.1:" + server.getAddress().getPort(),
                        "--suite-id", TestSuiteStabilityTestFixtures.SUITE_ID,
                        "--revision", Long.toString(TestSuiteStabilityTestFixtures.SUITE_REVISION),
                        "--fingerprint", TestSuiteStabilityTestFixtures.SUITE_FINGERPRINT,
                        "--client-request-id", TestSuiteStabilityTestFixtures.CLIENT_REQUEST_ID,
                        "--mode", "STABILITY", "--attempts", "21",
                        "--trusted-key-set-fingerprint",
                        stabilityFixture.keySet().snapshotFingerprint(),
                }, Map.of("RESOURCE_GATEWAY_TOKEN", "ci-secret-token"),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(error));
        assertThat(unbounded).isEqualTo(2);
        assertThat(requestPath).isEmpty();
    }

    @Test
    void wrongStabilityTrustPinProducesTerminalGateFailureNotInfrastructureSuccess()
            throws Exception {
        Path report = temporaryDirectory.resolve("ci/stability-wrong-pin.xml");

        int exit = ResourceGatewaySuiteCli.run(new String[]{
                        "--base-uri", "http://127.0.0.1:" + server.getAddress().getPort(),
                        "--suite-id", TestSuiteStabilityTestFixtures.SUITE_ID,
                        "--revision", Long.toString(TestSuiteStabilityTestFixtures.SUITE_REVISION),
                        "--fingerprint", TestSuiteStabilityTestFixtures.SUITE_FINGERPRINT,
                        "--client-request-id", TestSuiteStabilityTestFixtures.CLIENT_REQUEST_ID,
                        "--mode", "STABILITY",
                        "--trusted-key-set-fingerprint", "sha256:" + "8".repeat(64),
                        "--report", report.toString(),
                }, Map.of("RESOURCE_GATEWAY_TOKEN", "ci-secret-token"),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(Files.readString(report))
                .contains("KEY_SET_PIN_MISMATCH")
                .contains("STABILITY_ATTESTATION_UNVERIFIED")
                .contains("failures=\"2\"");
    }

    private void executeSuite(HttpExchange exchange) throws IOException {
        purpose = exchange.getRequestHeaders().getFirst("X-Purpose");
        authorization = exchange.getRequestHeaders().getFirst("Authorization");
        byte[] response = suiteResponse().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private void executeBlockedSuite(HttpExchange exchange) throws IOException {
        purpose = exchange.getRequestHeaders().getFirst("X-Purpose");
        authorization = exchange.getRequestHeaders().getFirst("Authorization");
        byte[] response = blockedSuiteResponse().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private void executeRunningSuite(HttpExchange exchange) throws IOException {
        purpose = exchange.getRequestHeaders().getFirst("X-Purpose");
        authorization = exchange.getRequestHeaders().getFirst("Authorization");
        byte[] response = runningSuiteResponse().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private void executeAdmissionSuite(HttpExchange exchange) throws IOException {
        purpose = exchange.getRequestHeaders().getFirst("X-Purpose");
        authorization = exchange.getRequestHeaders().getFirst("Authorization");
        byte[] response = TestSuiteRunAssertionsTest.schemaAdmissionSuiteResponse()
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private void executeMutationSuite(HttpExchange exchange) throws IOException {
        purpose = exchange.getRequestHeaders().getFirst("X-Purpose");
        authorization = exchange.getRequestHeaders().getFirst("Authorization");
        requestPath = exchange.getRequestURI().getPath();
        requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String responseBody = requestBody.contains("mutation-ci-failed")
                ? failedMutationSuiteResponse()
                : TestSuiteRunAssertionsTest.mutationSuiteResponse();
        byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private void executeStability(HttpExchange exchange) throws IOException {
        purpose = exchange.getRequestHeaders().getFirst("X-Purpose");
        authorization = exchange.getRequestHeaders().getFirst("Authorization");
        requestPath = exchange.getRequestURI().getPath();
        requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        ObjectNode request = (ObjectNode) JSON.readTree(requestBody);
        String requestVersion = request.path("schemaVersion").asText();
        ObjectNode response;
        if (TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_REQUEST_V3.equals(requestVersion)) {
            response = TestSuiteStabilityTestFixtures.rateStableResponse(
                    EvidenceVerificationSupport.sha256(request), stabilityFixture.keyPair());
        } else if (TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_REQUEST_V2
                .equals(requestVersion)) {
            response = TestSuiteStabilityTestFixtures.statisticalResponse(
                    EvidenceVerificationSupport.sha256(request), stabilityFixture.keyPair());
        } else {
            response = TestSuiteStabilityTestFixtures.response(
                    EvidenceVerificationSupport.sha256(request), stabilityFixture.keyPair());
        }
        ((ObjectNode) response.path("evidence"))
                .put("clientRequestId", request.path("clientRequestId").asText());
        if (request.path("clientRequestId").asText().contains("flaky")) {
            TestSuiteStabilityTestFixtures.makeFlaky(response, stabilityFixture.keyPair());
        } else {
            TestSuiteStabilityTestFixtures.seal(response, stabilityFixture.keyPair(), false);
        }
        respond(exchange, response.toString());
    }

    private void findEvidenceKeys(HttpExchange exchange) throws IOException {
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("payloadKind", "EVIDENCE_VERIFICATION_KEY_SET");
        envelope.put("payloadSchemaVersion", TestingProtocol.EVIDENCE_VERIFICATION_KEY_SET_V1);
        envelope.set("payload", stabilityFixture.keySet().rawSnapshot());
        respond(exchange, envelope.toString());
    }

    private static void respond(HttpExchange exchange, String responseBody) throws IOException {
        byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static String failedMutationSuiteResponse() throws IOException {
        ObjectNode response = (ObjectNode) JSON.readTree(
                TestSuiteRunAssertionsTest.mutationSuiteResponse());
        ObjectNode evidence = (ObjectNode) response.path("evidence");
        evidence.put("clientRequestId", "mutation-ci-failed");
        evidence.put("status", "COMPLETED_WITH_FAILURES");
        ObjectNode score = (ObjectNode) evidence.path("mutationScore");
        ((ObjectNode) score.path("policy")).put("minimumScoreBasisPoints", 6_000);
        score.put("status", "UNSATISFIED");
        score.putArray("reasons").add("MUTATION_SCORE_BELOW_THRESHOLD");
        ObjectNode promotion = (ObjectNode) evidence.path("promotion");
        promotion.put("status", "BLOCKED");
        promotion.putArray("reasons").add("MUTATION_SCORE_UNSATISFIED");
        return JSON.writeValueAsString(response);
    }

    private static String suiteResponse() {
        return """
                {"schemaVersion":"bloge.testSuiteExecutionResponse.v1","suiteRunId":"suite-run-982",
                 "evidenceFingerprint":"%1$s","evidence":{"schemaVersion":"bloge.testSuiteRunEvidence.v1",
                   "suiteRunId":"suite-run-982","status":"PASSED","clientRequestId":"pipeline-982-job-4",
                   "executionPurpose":"TEST_SUITE_EXECUTION",
                   "suiteRef":{"suiteId":"loan-policy","revision":3,"fingerprint":"%1$s"},
                   "target":{"kind":"GRAPH","id":"loanDecision","fingerprint":"%1$s"},
                   "startedAt":"2026-07-15T10:15:30Z","completedAt":"2026-07-15T10:15:31Z",
                   "caseResults":[{"caseId":"golden","caseType":"GOLDEN","status":"PASSED",
                     "runId":"run-golden","fixtureBundleRef":{"fixtureBundleId":"f1","revision":1,
                     "fingerprint":"%1$s"},"evidenceStatus":"PASSED","evidenceClass":"CERTIFIABLE",
                     "assertionsEvaluated":1,"assertionsPassed":1,"diagnosticCode":"","diagnostic":""}],
                   "coverage":{"status":"SATISFIED","minimumCases":1,"completedCases":1,
                     "requiredCaseTypes":["GOLDEN"],"observedCaseTypes":["GOLDEN"],
                     "missingCaseTypes":[],"requiredInvocationSiteIds":[],"observedInvocationSiteIds":[],
                     "missingInvocationSiteIds":[],"requiredEdgeTransfers":[],"observedEdgeTransfers":[],
                     "missingEdgeTransfers":[],"minimumAssertionsPerCase":1,
                     "assertionDensityViolations":[],"fixtureConsumptionViolations":[],
                     "allCasesCompleted":true},
                   "promotion":{"status":"ELIGIBLE","reasons":[],"allCasesPassed":true,
                     "certifiableCases":1,"minimumCertifiableCases":1,"targetCertificationEligible":true,
                     "coverageSatisfied":true,"allCasesCompleted":true},"diagnostics":[],"metadata":{}}}
                """.formatted(FINGERPRINT);
    }

    private static String blockedSuiteResponse() {
        return """
                {"schemaVersion":"bloge.testSuiteExecutionResponse.v1","suiteRunId":"suite-run-983",
                 "evidenceFingerprint":"%1$s","evidence":{"schemaVersion":"bloge.testSuiteRunEvidence.v1",
                   "suiteRunId":"suite-run-983","status":"COMPLETED_WITH_FAILURES",
                   "clientRequestId":"pipeline-983-job-4","executionPurpose":"TEST_SUITE_EXECUTION",
                   "suiteRef":{"suiteId":"blocked-policy","revision":3,"fingerprint":"%1$s"},
                   "target":{"kind":"GRAPH","id":"loanDecision","fingerprint":"%1$s"},
                   "startedAt":"2026-07-15T10:15:30Z","completedAt":"2026-07-15T10:15:31Z",
                   "caseResults":[{"caseId":"negative","caseType":"NEGATIVE","status":"FAILED",
                     "runId":"run-negative","fixtureBundleRef":{"fixtureBundleId":"f2","revision":1,
                     "fingerprint":"%1$s"},"evidenceStatus":"ASSERTION_FAILED",
                     "evidenceClass":"CERTIFIABLE","assertionsEvaluated":1,"assertionsPassed":0,
                     "diagnosticCode":"ASSERTION_FAILED","diagnostic":"private diagnostic"}],
                   "coverage":{"status":"SATISFIED","minimumCases":1,"completedCases":1,
                     "requiredCaseTypes":["NEGATIVE"],"observedCaseTypes":["NEGATIVE"],
                     "missingCaseTypes":[],"requiredInvocationSiteIds":[],"observedInvocationSiteIds":[],
                     "missingInvocationSiteIds":[],"requiredEdgeTransfers":[],"observedEdgeTransfers":[],
                     "missingEdgeTransfers":[],"minimumAssertionsPerCase":1,
                     "assertionDensityViolations":[],"fixtureConsumptionViolations":[],
                     "allCasesCompleted":true},
                   "promotion":{"status":"BLOCKED","reasons":["CASES_FAILED"],"allCasesPassed":false,
                     "certifiableCases":1,"minimumCertifiableCases":1,"targetCertificationEligible":true,
                     "coverageSatisfied":true,"allCasesCompleted":true},"diagnostics":[],"metadata":{}}}
                """.formatted(FINGERPRINT);
    }

    private static String runningSuiteResponse() {
        return """
                {"schemaVersion":"bloge.testSuiteExecutionResponse.v1","suiteRunId":"suite-run-984",
                 "evidenceFingerprint":"","evidence":{"schemaVersion":"bloge.testSuiteRunEvidence.v1",
                   "suiteRunId":"suite-run-984","status":"RUNNING",
                   "clientRequestId":"pipeline-984-job-4","executionPurpose":"TEST_SUITE_EXECUTION",
                   "suiteRef":{"suiteId":"running-policy","revision":3,"fingerprint":"%1$s"},
                   "target":{"kind":"GRAPH","id":"loanDecision","fingerprint":"%1$s"},
                   "startedAt":"2026-07-15T10:15:30Z","completedAt":null,
                   "caseResults":[{"caseId":"golden","caseType":"GOLDEN","status":"PENDING",
                     "runId":"","fixtureBundleRef":{"fixtureBundleId":"f3","revision":1,
                     "fingerprint":"%1$s"},"evidenceStatus":null,"evidenceClass":null,
                     "assertionsEvaluated":0,"assertionsPassed":0,"diagnosticCode":"","diagnostic":""}],
                   "coverage":{"status":"NOT_EVALUATED","minimumCases":1,"completedCases":0,
                     "requiredCaseTypes":["GOLDEN"],"observedCaseTypes":[],"missingCaseTypes":["GOLDEN"],
                     "requiredInvocationSiteIds":[],"observedInvocationSiteIds":[],
                     "missingInvocationSiteIds":[],"requiredEdgeTransfers":[],"observedEdgeTransfers":[],
                     "missingEdgeTransfers":[],"minimumAssertionsPerCase":1,
                     "assertionDensityViolations":[],"fixtureConsumptionViolations":[],
                     "allCasesCompleted":false},
                   "promotion":{"status":"NOT_EVALUATED","reasons":[],"allCasesPassed":false,
                     "certifiableCases":0,"minimumCertifiableCases":1,"targetCertificationEligible":true,
                     "coverageSatisfied":false,"allCasesCompleted":false},"diagnostics":[],"metadata":{}}}
                """.formatted(FINGERPRINT);
    }
}
