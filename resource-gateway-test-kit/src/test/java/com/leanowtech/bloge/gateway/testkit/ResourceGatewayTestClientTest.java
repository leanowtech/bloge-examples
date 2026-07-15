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
        OperatorTargetDescriptor operator = client.describeOperatorTarget("customer.normalize/v2");
        FixtureBundleRevision registered = client.registerFixture("fixture/approved", registration);
        FixtureBundleRevision found = client.findFixture("fixture/approved", 3);
        TestRun executed = client.execute(execution);
        TestRun operatorRun = client.executeOperator("customer.normalize/v2", execution);
        TestRunBatch batch = client.executeBatch(List.of(execution, execution.deepCopy()));
        TestRun queried = client.findRun("run/42", ResourceGatewayTestClient.Verbosity.FULL);

        assertThat(target.graphId()).isEqualTo("loan decision/v2");
        assertThat(target.fingerprint()).isEqualTo(FINGERPRINT);
        assertThat(target.certificationEligible()).isTrue();
        assertThat(operator.operatorRef()).isEqualTo("customer.normalize/v2");
        assertThat(operator.testabilityClass()).isEqualTo("EXECUTABLE_UNIT");
        assertThat(operator.executionSupported()).isTrue();
        assertThat(operator.composabilityFingerprint()).isEqualTo(FINGERPRINT);
        assertThat(operator.composability().dependencyMode()).isEqualTo("NONE");
        assertThat(operator.composability().globalStateFree()).isTrue();
        assertThat(operator.composability().conformanceFingerprint()).isEqualTo(FINGERPRINT);
        assertThat(registered.fixtureBundleId()).isEqualTo("fixture/approved");
        assertThat(found.revision()).isEqualTo(3);
        assertThat(executed.runId()).isEqualTo("run-42");
        assertThat(operatorRun.runId()).isEqualTo("run-42");
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
                .containsExactly("TEST_EXECUTION", "TEST_EXECUTION", "TEST_FIXTURE_WRITE",
                        "TEST_FIXTURE_READ", "TEST_EXECUTION", "TEST_EXECUTION",
                        "TEST_EXECUTION", "TEST_EXECUTION");
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.authorization()).isEqualTo("Bearer super-secret-token");
            assertThat(request.correlationId()).isNotBlank();
            assertThat(request.accept()).isEqualTo("application/json");
        });
        assertThat(requests.get(0).rawPath()).endsWith("/loan%20decision%2Fv2");
        assertThat(requests.get(1).rawPath()).endsWith("/customer.normalize%2Fv2");
        assertThat(requests.get(3).rawQuery()).isEqualTo("revision=3");
        assertThat(requests.get(7).rawQuery()).isEqualTo("verbosity=FULL");
        assertThat(requests.get(6).body().path("schemaVersion").asText())
                .isEqualTo(TestingProtocol.TEST_EXECUTION_BATCH_REQUEST_V1);
    }

    @Test
    void registersExecutesAndQueriesOneExactImmutableSuite() throws Exception {
        ResourceGatewayTestClient client = client();
        ObjectNode registration = JSON.createObjectNode();
        registration.put("schemaVersion", TestingProtocol.TEST_SUITE_REGISTRATION_REQUEST_V1);
        registration.set("testSuite", JSON.readTree(storedSuiteResponse()).path("suite").deepCopy());

        TestSuiteRevision registered = client.registerSuite("suite/policy", registration);
        TestSuiteRevision found = client.findSuite("suite/policy", 7);
        TestSuiteRun executed = client.executeSuite("suite/policy", 7, FINGERPRINT,
                "pipeline/982", ResourceGatewayTestClient.SuiteStrategy.FAIL_FAST,
                Map.of("source", "ci"));
        TestSuiteRun queried = client.findSuiteRun("suite-run/42");

        assertThat(registered.suiteId()).isEqualTo("suite/policy");
        assertThat(registered.revision()).isEqualTo(7);
        assertThat(registered.fingerprint()).isEqualTo(FINGERPRINT);
        assertThat(registered.targetKind()).isEqualTo("OPERATOR");
        assertThat(registered.targetId()).isEqualTo("customer.normalize/v2");
        assertThat(registered.caseCount()).isEqualTo(2);
        assertThat(found.exactRef()).isEqualTo("suite/policy@7#" + FINGERPRINT);
        assertThat(executed.suiteRunId()).isEqualTo("suite-run/42");
        assertThat(executed.status()).isEqualTo(TestSuiteRun.Status.PASSED);
        assertThat(executed.coverageStatus()).isEqualTo(TestSuiteRun.CoverageStatus.SATISFIED);
        assertThat(executed.promotionStatus()).isEqualTo(TestSuiteRun.PromotionStatus.ELIGIBLE);
        assertThat(executed.passed()).isTrue();
        assertThat(executed.promotionEligible()).isTrue();
        assertThat(executed.caseResults()).extracting(TestSuiteRun.CaseResult::caseId)
                .containsExactly("golden", "boundary");
        assertThat(executed.caseResults()).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo(TestSuiteRun.CaseStatus.PASSED);
            assertThat(result.runId()).startsWith("run-");
            assertThat(result.fixtureFingerprint()).isEqualTo(FINGERPRINT);
        });
        assertThat(queried.suiteRunId()).isEqualTo(executed.suiteRunId());

        assertThat(requests).extracting(CapturedRequest::purpose)
                .containsExactly("TEST_SUITE_WRITE", "TEST_SUITE_READ", "TEST_EXECUTION", "TEST_EXECUTION");
        assertThat(requests.get(0).method()).isEqualTo("PUT");
        assertThat(requests.get(0).rawPath()).endsWith("/suites/suite%2Fpolicy");
        assertThat(requests.get(1).rawQuery()).isEqualTo("revision=7");
        assertThat(requests.get(2).rawPath()).endsWith("/suites/suite%2Fpolicy/executions");
        assertThat(requests.get(2).body().path("schemaVersion").asText())
                .isEqualTo(TestingProtocol.TEST_SUITE_EXECUTION_REQUEST_V1);
        assertThat(requests.get(2).body().path("suiteRef").path("revision").asLong()).isEqualTo(7);
        assertThat(requests.get(2).body().path("suiteRef").path("fingerprint").asText()).isEqualTo(FINGERPRINT);
        assertThat(requests.get(2).body().path("clientRequestId").asText()).isEqualTo("pipeline/982");
        assertThat(requests.get(2).body().path("strategy").asText()).isEqualTo("FAIL_FAST");
        assertThat(requests.get(2).body().path("metadata").path("source").asText()).isEqualTo("ci");
        assertThat(requests.get(3).rawPath()).endsWith("/suite-executions/suite-run%2F42");
    }

    @Test
    void materializesBuiltInGraphCatalogWithTypedExactReferences() {
        ResourceGatewayTestClient client = client();

        TestSuiteCatalogMaterialization catalog =
                client.materializeBuiltInGraphContractCatalog();

        assertThat(catalog.catalogId()).isEqualTo("resource-gateway.built-in-graph-contracts");
        assertThat(catalog.catalogFingerprint()).isEqualTo(FINGERPRINT);
        assertThat(catalog.totalSuites()).isEqualTo(1);
        assertThat(catalog.totalCases()).isEqualTo(1);
        assertThat(catalog.suites()).singleElement().satisfies(asset -> {
            assertThat(asset.sourceSuiteId()).isEqualTo("loan-policy");
            assertThat(asset.graphName()).isEqualTo("loanDecisionPolicy");
            assertThat(asset.suiteRef().exactRef())
                    .isEqualTo("rg-built-in-loan-policy@7#" + FINGERPRINT);
            assertThat(asset.fixtureRefs()).singleElement().satisfies(fixture -> {
                assertThat(fixture.fixtureBundleId()).isEqualTo("rg-built-in-loan-policy-case-001");
                assertThat(fixture.revision()).isEqualTo(3);
            });
        });
        JsonNode mutable = catalog.rawResponse();
        ((ObjectNode) mutable).put("catalogId", "mutated");
        assertThat(catalog.rawResponse().path("catalogId").asText())
                .isEqualTo("resource-gateway.built-in-graph-contracts");
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("PUT");
            assertThat(request.rawPath()).endsWith("/catalogs/gateway-graph-contract-v1");
            assertThat(request.purpose()).isEqualTo("TEST_SUITE_WRITE");
        });
    }

    @Test
    void rejectsCatalogResponseWithSelfInconsistentAggregateCounts() throws Exception {
        ObjectNode response = (ObjectNode) JSON.readTree(catalogMaterializationResponse());
        response.put("totalCases", 2);

        assertThatThrownBy(() -> TestSuiteCatalogMaterialization.from(response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("case count");
    }

    @Test
    void rejectsCatalogProjectionThatReusesAFixtureAcrossSuites() {
        var fixture = new TestSuiteCatalogMaterialization.ExactFixtureRef(
                "fixture-a", 1, FINGERPRINT);
        var first = new TestSuiteCatalogMaterialization.SuiteAsset(
                "source-a", "graph-a", 1,
                new TestSuiteCatalogMaterialization.ExactSuiteRef("suite-a", 1, FINGERPRINT),
                List.of(fixture));
        var second = new TestSuiteCatalogMaterialization.SuiteAsset(
                "source-b", "graph-b", 1,
                new TestSuiteCatalogMaterialization.ExactSuiteRef("suite-b", 1, FINGERPRINT),
                List.of(fixture));

        assertThatThrownBy(() -> new TestSuiteCatalogMaterialization(
                "catalog-a", FINGERPRINT, "tenant", "test", 2, 2,
                List.of(first, second), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique exact fixture");
    }

    @Test
    void rejectsInexactSuiteIdentityBeforeAnyNetworkCall() {
        ResourceGatewayTestClient client = client();

        assertThatThrownBy(() -> client.executeSuite("loan-policy", 1, "sha256:short", "pipeline-1",
                ResourceGatewayTestClient.SuiteStrategy.COLLECT_ALL, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("full lowercase SHA-256");
        assertThatThrownBy(() -> client.executeSuite("loan-policy", 1, FINGERPRINT, " ",
                ResourceGatewayTestClient.SuiteStrategy.COLLECT_ALL, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clientRequestId");
        assertThat(requests).isEmpty();
    }

    @Test
    void rejectsSuiteExecutionResponseBoundToAnotherRequestIntent() {
        ResourceGatewayTestClient client = client();

        assertThatThrownBy(() -> client.executeSuite("different-suite", 7, FINGERPRINT,
                "pipeline/982", ResourceGatewayTestClient.SuiteStrategy.COLLECT_ALL, Map.of()))
                .isInstanceOf(ResourceGatewayTestException.class)
                .hasMessageContaining("response identity")
                .hasMessageNotContaining("private");

        assertThat(requests).hasSize(1);
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

    @Test
    void rejectsMalformedChildRunWithoutRetainingPayloadBearingCause() {
        ResourceGatewayTestClient client = client();

        assertThatThrownBy(() -> client.findRun("malformed", ResourceGatewayTestClient.Verbosity.STANDARD))
                .isInstanceOfSatisfying(ResourceGatewayTestException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("RG.TESTKIT.RESPONSE_CONTRACT_INVALID");
                    assertThat(failure.getCause()).isNull();
                    assertThat(failure.getMessage()).doesNotContain("private-child-payload");
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
        if (path.endsWith("/malformed")) {
            respond(exchange, 200, "{\"schemaVersion\":\"bloge.testExecutionResponse.v1\","
                    + "\"runId\":\"private-child-payload\",\"evidence\":{\"status\":\"NOT_A_STATUS\"}}");
            return;
        }
        if (path.endsWith("/catalogs/gateway-graph-contract-v1")) {
            respond(exchange, 200, catalogMaterializationResponse());
        } else if (path.contains("/suite-executions/") || path.endsWith("/executions") && path.contains("/suites/")) {
            respond(exchange, 200, suiteRunResponse());
        } else if (path.contains("/suites/")) {
            respond(exchange, 200, storedSuiteResponse());
        } else if ("GET".equals(exchange.getRequestMethod()) && path.contains("/targets/operators/")) {
            respond(exchange, 200, operatorTargetResponse());
        } else if (path.contains("/targets/graphs/")) {
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

    private static String operatorTargetResponse() {
        return """
                {"schemaVersion":"bloge.testOperatorTargetDescriptor.v2",
                 "target":{"kind":"OPERATOR","id":"customer.normalize/v2","fingerprint":"%1$s"},
                 "implementationFingerprint":"%1$s","runtimeBindingStateFingerprint":"%1$s",
                 "schemaFingerprint":"%1$s","composabilityFingerprint":"%1$s",
                 "composabilityManifest":{"schemaVersion":"bloge.operatorComposabilityManifest.v1",
                   "dependencyMode":"NONE","dependencies":[],"executionServices":[],
                   "globalStateFree":true,"conformanceSuiteRef":"suite:normalize","conformanceFingerprint":"%1$s"},
                 "inputSchema":{},"outputSchema":{},"executionModel":"SYNCHRONOUS",
                 "sideEffectType":"READ_ONLY","idempotency":"IDEMPOTENT","sideEffectProtocol":{},
                 "testabilityClass":"EXECUTABLE_UNIT","resourceDependencyFingerprints":{},
                 "dependencyPolicy":"NONE_DECLARED","executionSupported":true,
                 "certificationEligible":true,"certificationRequirements":[],"certificationGaps":[]}
                """.formatted(FINGERPRINT);
    }

    private static String storedFixtureResponse() {
        return """
                {"schemaVersion":"bloge.storedFixtureBundle.v1","tenantId":"tenant",
                 "environmentId":"test","fixtureBundleId":"fixture/approved","revision":3,
                 "fingerprint":"%s","bundle":{},"createdAt":"2026-07-15T10:15:30Z","createdBy":"ci"}
                """.formatted(FINGERPRINT);
    }

    private static String storedSuiteResponse() {
        return """
                {"schemaVersion":"bloge.storedTestSuite.v1","tenantId":"tenant",
                 "environmentId":"test","suiteId":"suite/policy","revision":7,
                 "fingerprint":"%1$s","suite":{"schemaVersion":"bloge.testSuite.v1",
                   "suiteId":"suite/policy","revision":7,
                   "target":{"kind":"OPERATOR","id":"customer.normalize/v2","fingerprint":"%1$s"},
                   "classification":"INTERNAL","cases":[
                     {"caseId":"golden","caseType":"GOLDEN","input":{},
                      "fixtureBundleRef":{"fixtureBundleId":"fixture-golden","revision":1,
                        "fingerprint":"%1$s"},"tags":[],"metadata":{}},
                     {"caseId":"boundary","caseType":"BOUNDARY","input":{},
                      "fixtureBundleRef":{"fixtureBundleId":"fixture-boundary","revision":1,
                        "fingerprint":"%1$s"},"tags":[],"metadata":{}}],
                   "coveragePolicy":{"minimumCases":2,"requiredCaseTypes":["GOLDEN","BOUNDARY"],
                     "requiredInvocationSiteIds":[],"requiredEdgeTransfers":[],
                     "minimumAssertionsPerCase":1,"requireAllFixtureRulesConsumed":true},
                   "promotionPolicy":{"requireAllCasesPassed":true,"minimumCertifiableCases":2,
                     "requireTargetCertificationEligible":true},"metadata":{}},
                 "createdAt":"2026-07-15T10:15:30Z","createdBy":"ci"}
                """.formatted(FINGERPRINT);
    }

    private static String suiteRunResponse() {
        return """
                {"schemaVersion":"bloge.testSuiteExecutionResponse.v1","suiteRunId":"suite-run/42",
                 "evidenceFingerprint":"%1$s","evidence":{"schemaVersion":"bloge.testSuiteRunEvidence.v1",
                   "suiteRunId":"suite-run/42","clientRequestId":"pipeline/982","status":"PASSED",
                   "executionPurpose":"TEST_SUITE_EXECUTION",
                   "suiteRef":{"suiteId":"suite/policy","revision":7,"fingerprint":"%1$s"},
                   "target":{"kind":"OPERATOR","id":"customer.normalize/v2","fingerprint":"%1$s"},
                   "startedAt":"2026-07-15T10:15:30Z","completedAt":"2026-07-15T10:15:31Z",
                   "caseResults":[
                     {"caseId":"golden","caseType":"GOLDEN",
                      "fixtureBundleRef":{"fixtureBundleId":"fixture-golden","revision":1,"fingerprint":"%1$s"},
                      "status":"PASSED","runId":"run-golden","evidenceStatus":"PASSED",
                      "evidenceClass":"CERTIFIABLE","assertionsEvaluated":1,"assertionsPassed":1,
                      "diagnosticCode":"","diagnostic":""},
                     {"caseId":"boundary","caseType":"BOUNDARY",
                      "fixtureBundleRef":{"fixtureBundleId":"fixture-boundary","revision":1,"fingerprint":"%1$s"},
                      "status":"PASSED","runId":"run-boundary","evidenceStatus":"PASSED",
                      "evidenceClass":"CERTIFIABLE","assertionsEvaluated":1,"assertionsPassed":1,
                      "diagnosticCode":"","diagnostic":""}],
                   "coverage":{"status":"SATISFIED","minimumCases":2,"completedCases":2,
                     "requiredCaseTypes":["GOLDEN","BOUNDARY"],"observedCaseTypes":["GOLDEN","BOUNDARY"],
                     "missingCaseTypes":[],"requiredInvocationSiteIds":[],"observedInvocationSiteIds":[],
                     "missingInvocationSiteIds":[],"requiredEdgeTransfers":[],"observedEdgeTransfers":[],
                     "missingEdgeTransfers":[],"minimumAssertionsPerCase":1,
                     "assertionDensityViolations":[],"fixtureConsumptionViolations":[],"allCasesCompleted":true},
                   "promotion":{"status":"ELIGIBLE","reasons":[],"allCasesPassed":true,
                     "certifiableCases":2,"minimumCertifiableCases":2,"targetCertificationEligible":true,
                     "coverageSatisfied":true,"allCasesCompleted":true},
                   "diagnostics":[],"metadata":{"private":"not-projected"}}}
                """.formatted(FINGERPRINT);
    }

    private static String catalogMaterializationResponse() {
        return """
                {"schemaVersion":"bloge.testSuiteCatalogMaterialization.v1",
                 "catalogId":"resource-gateway.built-in-graph-contracts","catalogFingerprint":"%1$s",
                 "tenantId":"tenant","environmentId":"test","totalSuites":1,"totalCases":1,
                 "suites":[{"sourceSuiteId":"loan-policy","graphName":"loanDecisionPolicy",
                   "caseCount":1,"suiteRef":{"suiteId":"rg-built-in-loan-policy","revision":7,
                     "fingerprint":"%1$s"},"fixtureBundleRefs":[
                     {"fixtureBundleId":"rg-built-in-loan-policy-case-001","revision":3,
                      "fingerprint":"%1$s"}]}]}
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
