package com.leanowtech.bloge.gateway.testkit;

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

    private HttpServer server;
    private volatile String purpose = "";
    private volatile String authorization = "";

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/testing/suites/loan-policy/executions", this::executeSuite);
        server.createContext("/api/testing/suites/blocked-policy/executions", this::executeBlockedSuite);
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
                }, Map.of("RESOURCE_GATEWAY_TOKEN", token),
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

    private static String suiteResponse() {
        return """
                {"schemaVersion":"bloge.testSuiteExecutionResponse.v1","suiteRunId":"suite-run-982",
                 "evidenceFingerprint":"%1$s","evidence":{"status":"PASSED",
                   "clientRequestId":"pipeline-982-job-4",
                   "suiteRef":{"suiteId":"loan-policy","revision":3,"fingerprint":"%1$s"},
                   "target":{"kind":"GRAPH","id":"loanDecision","fingerprint":"%1$s"},
                   "caseResults":[{"caseId":"golden","caseType":"GOLDEN","status":"PASSED",
                     "runId":"run-golden","fixtureBundleRef":{"fixtureBundleId":"f1","revision":1,
                     "fingerprint":"%1$s"},"evidenceStatus":"PASSED","evidenceClass":"CERTIFIABLE",
                     "assertionsEvaluated":1,"assertionsPassed":1,"diagnosticCode":"","diagnostic":""}],
                   "coverage":{"status":"SATISFIED","minimumCases":1,"completedCases":1,
                     "missingCaseTypes":[],"missingInvocationSiteIds":[],"missingEdgeTransfers":[],
                     "assertionDensityViolations":[],"fixtureConsumptionViolations":[]},
                   "promotion":{"status":"ELIGIBLE","reasons":[],"allCasesPassed":true,
                     "certifiableCases":1,"minimumCertifiableCases":1,"targetCertificationEligible":true,
                     "coverageSatisfied":true,"allCasesCompleted":true},"diagnostics":[],"metadata":{}}}
                """.formatted(FINGERPRINT);
    }

    private static String blockedSuiteResponse() {
        return """
                {"schemaVersion":"bloge.testSuiteExecutionResponse.v1","suiteRunId":"suite-run-983",
                 "evidenceFingerprint":"%1$s","evidence":{"status":"COMPLETED_WITH_FAILURES",
                   "clientRequestId":"pipeline-983-job-4",
                   "suiteRef":{"suiteId":"blocked-policy","revision":3,"fingerprint":"%1$s"},
                   "target":{"kind":"GRAPH","id":"loanDecision","fingerprint":"%1$s"},
                   "caseResults":[{"caseId":"negative","caseType":"NEGATIVE","status":"FAILED",
                     "runId":"run-negative","fixtureBundleRef":{"fixtureBundleId":"f2","revision":1,
                     "fingerprint":"%1$s"},"evidenceStatus":"ASSERTION_FAILED",
                     "evidenceClass":"CERTIFIABLE","assertionsEvaluated":1,"assertionsPassed":0,
                     "diagnosticCode":"ASSERTION_FAILED","diagnostic":"private diagnostic"}],
                   "coverage":{"status":"SATISFIED","minimumCases":1,"completedCases":1,
                     "missingCaseTypes":[],"missingInvocationSiteIds":[],"missingEdgeTransfers":[],
                     "assertionDensityViolations":[],"fixtureConsumptionViolations":[]},
                   "promotion":{"status":"BLOCKED","reasons":["CASES_FAILED"],"allCasesPassed":false,
                     "certifiableCases":1,"minimumCertifiableCases":1,"targetCertificationEligible":true,
                     "coverageSatisfied":true,"allCasesCompleted":true},"diagnostics":[],"metadata":{}}}
                """.formatted(FINGERPRINT);
    }
}
