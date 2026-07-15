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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceGatewayTestClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);

    private HttpServer server;
    private final List<CapturedRequest> requests = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/testing", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void callsEveryPublicEndpointWithLeastPrivilegePurposeAndTypedResults() {
        ResourceGatewayTestClient client = client();
        ObjectNode execution = JSON.createObjectNode().put("case", "approved");
        ObjectNode registration = JSON.createObjectNode().put("fixture", "loan-approved");

        GraphTargetDescriptor target = client.describeGraphTarget("loan decision/v2");
        FixtureBundleRevision registered = client.registerFixture("fixture/approved", registration);
        FixtureBundleRevision found = client.findFixture("fixture/approved", 3);
        TestRun executed = client.execute(execution);
        TestRunBatch batch = client.executeBatch(List.of(execution, execution.deepCopy()));
        TestRun queried = client.findRun("run/42", ResourceGatewayTestClient.Verbosity.FULL);

        assertThat(target.graphId()).isEqualTo("loan decision/v2");
        assertThat(target.fingerprint()).isEqualTo(FINGERPRINT);
        assertThat(target.certificationEligible()).isTrue();
        assertThat(registered.fixtureBundleId()).isEqualTo("fixture/approved");
        assertThat(found.revision()).isEqualTo(3);
        assertThat(executed.runId()).isEqualTo("run-42");
        assertThat(executed.status()).isEqualTo(TestRun.Status.PASSED);
        assertThat(executed.nodeTraces()).singleElement().satisfies(node -> {
            assertThat(node.invocationSiteId()).isEqualTo("/root/credit#primary");
            assertThat(node.graphPath()).isEqualTo("/root");
            assertThat(node.correlationKey()).isEqualTo("application-42");
            assertThat(node.occurrence()).isEqualTo(2);
            assertThat(node.graphOccurrence()).isEqualTo(1);
            assertThat(node.attempts()).extracting(TestRun.AttemptTrace::attempt)
                    .containsExactly(1, 2);
        });
        assertThat(executed.edgeTraces()).singleElement().satisfies(edge -> {
            assertThat(edge.status()).isEqualTo("TRANSFERRED");
            assertThat(edge.graphOccurrence()).isEqualTo(1);
            assertThat(edge.fromInvocationSiteId()).isEqualTo("/root/input#primary");
            assertThat(edge.toInvocationSiteId()).isEqualTo("/root/credit#primary");
        });
        assertThat(batch.runs()).hasSize(2);
        assertThat(batch.exitCode()).isZero();
        assertThat(queried.evidenceClass()).isEqualTo(TestRun.EvidenceClass.CERTIFIABLE);
        JsonNode mutableCopy = queried.rawResponse();
        ((ObjectNode) mutableCopy).put("runId", "mutated");
        assertThat(queried.rawResponse().path("runId").asText()).isEqualTo("run-42");

        assertThat(requests).extracting(CapturedRequest::purpose)
                .containsExactly("TEST_EXECUTION", "TEST_FIXTURE_WRITE", "TEST_FIXTURE_READ",
                        "TEST_EXECUTION", "TEST_EXECUTION", "TEST_EXECUTION");
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.authorization()).isEqualTo("Bearer super-secret-token");
            assertThat(request.correlationId()).isNotBlank();
            assertThat(request.accept()).isEqualTo("application/json");
        });
        assertThat(requests.get(0).rawPath()).endsWith("/loan%20decision%2Fv2");
        assertThat(requests.get(2).rawQuery()).isEqualTo("revision=3");
        assertThat(requests.get(5).rawQuery()).isEqualTo("verbosity=FULL");
        assertThat(requests.get(4).body().path("schemaVersion").asText())
                .isEqualTo(TestingProtocol.TEST_EXECUTION_BATCH_REQUEST_V1);
    }

    @Test
    void mapsProblemDetailsWithoutLeakingCredentialOrRequestBody() {
        ResourceGatewayTestClient client = client();
        ObjectNode body = JSON.createObjectNode().put("private", "customer-secret-payload");

        assertThatThrownBy(() -> client.execute(body))
                .isInstanceOfSatisfying(ResourceGatewayTestException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(409);
                    assertThat(failure.code()).isEqualTo("RG.TEST.FIXTURE_CONFLICT");
                    assertThat(failure.retryable()).isTrue();
                    assertThat(failure.correlationId()).isEqualTo("corr-409");
                    assertThat(failure.getMessage())
                            .doesNotContain("customer-secret-payload")
                            .doesNotContain("super-secret-token")
                            .doesNotContain("server-private-detail");
                });
    }

    @Test
    void stripsControlCharactersFromProblemTitles() {
        ResourceGatewayTestException failure = new ResourceGatewayTestException(400, "RG.TEST.BAD",
                "first line\r\nforged log line", false, "corr", null);

        assertThat(failure.getMessage()).doesNotContain("\r").doesNotContain("\n");
        assertThat(failure.title()).isEqualTo("first line  forged log line");
    }

    @Test
    void rejectsOversizedResponsesWithBoundedTransportError() {
        ResourceGatewayTestClient client = ResourceGatewayTestClient.builder(baseUri())
                .bearerToken(() -> "super-secret-token")
                .requestTimeout(Duration.ofSeconds(2))
                .maxResponseBytes(128)
                .build();

        assertThatThrownBy(() -> client.findRun("oversized", ResourceGatewayTestClient.Verbosity.STANDARD))
                .isInstanceOfSatisfying(ResourceGatewayTestException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("RG.TESTKIT.RESPONSE_TOO_LARGE");
                    assertThat(failure.getMessage()).doesNotContain("sensitive-response-content");
                });
    }

    private ResourceGatewayTestClient client() {
        return ResourceGatewayTestClient.builder(baseUri())
                .bearerToken(() -> "super-secret-token")
                .requestTimeout(Duration.ofSeconds(2))
                .build();
    }

    private URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] requestBytes = exchange.getRequestBody().readAllBytes();
        JsonNode body = requestBytes.length == 0 ? JSON.nullNode() : JSON.readTree(requestBytes);
        requests.add(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                exchange.getRequestURI().getRawQuery(), exchange.getRequestHeaders().getFirst("X-Purpose"),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("X-Correlation-Id"),
                exchange.getRequestHeaders().getFirst("Accept"), body));

        String path = exchange.getRequestURI().getRawPath();
        if ("POST".equals(exchange.getRequestMethod()) && path.endsWith("/executions")
                && body.has("private")) {
            respond(exchange, 409, """
                    {"schemaVersion":"toolStudio.resourceGateway.problem.v1",
                     "type":"urn:test","title":"Fixture revision conflicts with an immutable revision.",
                     "status":409,"code":"RG.TEST.FIXTURE_CONFLICT","retryable":true,
                     "correlationId":"corr-409","details":{"private":"server-private-detail"}}
                    """);
            return;
        }
        if (path.endsWith("/oversized")) {
            respond(exchange, 200, "{\"value\":\"" + "sensitive-response-content".repeat(20) + "\"}");
            return;
        }
        if (path.contains("/targets/graphs/")) {
            respond(exchange, 200, targetResponse());
        } else if (path.contains("/fixture-bundles/")) {
            respond(exchange, 200, storedFixtureResponse());
        } else if (path.endsWith("/executions/batch")) {
            respond(exchange, 200, "{\"schemaVersion\":\"bloge.testExecutionBatchResponse.v1\",\"executions\":["
                    + runResponse() + "," + runResponse() + "]}");
        } else {
            respond(exchange, 200, runResponse());
        }
    }

    private static String targetResponse() {
        return """
                {"schemaVersion":"bloge.testGraphTargetDescriptor.v1",
                 "target":{"kind":"GRAPH","id":"loan decision/v2","fingerprint":"%s"},
                 "contract":{},"resourceDependencyFingerprints":{},
                 "dependencyPolicy":"CONSERVATIVE_ALL_REGISTERED",
                 "certificationEligible":true,"certificationGaps":[]}
                """.formatted(FINGERPRINT);
    }

    private static String storedFixtureResponse() {
        return """
                {"schemaVersion":"bloge.storedFixtureBundle.v1","tenantId":"tenant",
                 "environmentId":"test","fixtureBundleId":"fixture/approved","revision":3,
                 "fingerprint":"%s","bundle":{},"createdAt":"2026-07-15T10:15:30Z","createdBy":"ci"}
                """.formatted(FINGERPRINT);
    }

    private static String runResponse() {
        return """
                {"schemaVersion":"bloge.testExecutionResponse.v1","runId":"run-42",
                 "target":{"kind":"GRAPH","id":"loanDecision","fingerprint":"%1$s"},
                 "fixtureBundleRef":{"source":"STORED","fixtureBundleId":"fixture","revision":3,
                                      "fingerprint":"%1$s"},
                 "plan":{"planFingerprint":"%1$s"},
                 "evidence":{"schemaVersion":"bloge.testRunEvidence.v1","runId":"run-42",
                   "status":"PASSED","evidenceClass":"CERTIFIABLE",
                   "targetFingerprint":"%1$s","fixtureBundleFingerprint":"%1$s",
                   "planFingerprint":"%1$s","nodeTrace":[{"nodeId":"credit","operatorRef":"httpResource",
                     "status":"MOCKED","fidelity":"TRANSPORT_LEVEL","input":"private-input",
                     "output":"private-output","errorCode":"","durationMs":2,
                     "invocationSiteId":"/root/credit#primary","graphPath":"/root",
                     "correlationKey":"application-42","occurrence":2,"graphOccurrence":1,
                     "attempts":[
                       {"attempt":1,"status":"FAILED","fidelity":"TRANSPORT_LEVEL",
                        "input":"private-attempt-input","output":null,"errorCode":"TIMEOUT","durationMs":1},
                       {"attempt":2,"status":"MOCKED","fidelity":"TRANSPORT_LEVEL",
                        "input":"private-attempt-input","output":"private-attempt-output",
                        "errorCode":"","durationMs":1}]}],
                   "edgeTrace":[{"edgeId":"input->credit","status":"TRANSFERRED",
                     "value":"private-edge-value","graphPath":"/root",
                     "correlationKey":"application-42","graphOccurrence":1,
                     "fromInvocationSiteId":"/root/input#primary",
                     "toInvocationSiteId":"/root/credit#primary"}],
                   "fixtureConsumptions":[{"ruleId":"credit","uses":1,"required":true,"status":"SATISFIED"}],
                   "assertionResults":[{"scope":"OUTPUT_PATH","path":"/approved","passed":true,
                     "diagnostic":""}],"diagnostics":[]}}
                """.formatted(FINGERPRINT);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record CapturedRequest(String method, String rawPath, String rawQuery, String purpose,
                                   String authorization, String correlationId, String accept, JsonNode body) {
    }
}
