package com.leanowtech.bloge.gateway.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.resource.ParameterMapping;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.resource.ResponseProtocol;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.visual.resource.InMemoryResourceDesignContractRegistry;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContractBootstrap;
import com.leanowtech.bloge.gateway.visual.simulation.JsonSchemaSampleGenerator;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GatewayGraphContractTestServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void tableSuiteRunsResourceGraphWithMockedDownstreamResources() throws IOException {
        GatewayGraphContractTestService service = testService("loan-decision-policy");

        GatewayGraphContractTestSuiteResult result = service.run(new GatewayGraphContractTestSuiteRequest(
                "loanDecisionPolicy",
                List.of(
                        new GatewayGraphContractTestCase(
                                "prime applicant",
                                Map.of("applicantId", "prime", "requestedAmount", 450_000.0),
                                List.of(new GatewayGraphResourceMock(
                                        "loan-applicant-service.getProfile",
                                        Map.of("applicantId", "prime"),
                                        Map.of("applicantId", "prime", "score", 780, "segment", "private-bank"))),
                                "assembleLoanDecision",
                                List.of(new GatewayGraphTestAssertion(
                                        GatewayGraphTestAssertion.Mode.PATH_EQUALS,
                                        "/policy/ruleId",
                                        "R1")),
                                Map.of("loanPolicy", List.of(new GatewayGraphTestAssertion(
                                        GatewayGraphTestAssertion.Mode.PATH_EQUALS,
                                        "/decision",
                                        "approved")))),
                        new GatewayGraphContractTestCase(
                                "declined applicant",
                                Map.of("applicantId", "decline", "requestedAmount", 120_000.0),
                                List.of(new GatewayGraphResourceMock(
                                        "loan-applicant-service.getProfile",
                                        Map.of("applicantId", "decline"),
                                        Map.of("applicantId", "decline", "score", 590, "segment", "new"))),
                                "assembleLoanDecision",
                                List.of(new GatewayGraphTestAssertion(
                                        GatewayGraphTestAssertion.Mode.PATH_EQUALS,
                                        "/policy/ruleId",
                                        "R4")),
                                Map.of("loanPolicy", List.of(new GatewayGraphTestAssertion(
                                        GatewayGraphTestAssertion.Mode.PATH_EQUALS,
                                        "/decision",
                                        "declined"))))
                )));

        assertThat(result.passed()).as("contract result: %s", result).isTrue();
        assertThat(result.totalCases()).isEqualTo(2);
        assertThat(result.passedCases()).isEqualTo(2);
        assertThat(result.coverage().contractOutputSchemaValidated()).isEqualTo(2);
        assertThat(result.coverage().assertionCount()).isEqualTo(4);
        assertThat(result.results())
                .allSatisfy(caseResult -> {
                    assertThat(caseResult.outputConformsToSchema()).isTrue();
                    assertThat(caseResult.mockedResourceInvocations()).hasSize(1);
                    assertThat(caseResult.diagnostics()).isEmpty();
                    assertThat(caseResult.assertionCount()).isEqualTo(2);
                });
    }

    @Test
    void tableSuiteFailsFastWhenContextViolatesGraphInputSchema() throws IOException {
        GatewayGraphContractTestService service = testService("loan-decision-policy");

        GatewayGraphContractTestSuiteResult result = service.run(new GatewayGraphContractTestSuiteRequest(
                "loanDecisionPolicy",
                List.of(new GatewayGraphContractTestCase(
                        "missing amount",
                        Map.of("applicantId", "prime"),
                        List.of(new GatewayGraphResourceMock(
                                "loan-applicant-service.getProfile",
                                Map.of("applicantId", "prime"),
                                Map.of("applicantId", "prime", "score", 780))),
                        "assembleLoanDecision",
                        List.of()))));

        assertThat(result.passed()).isFalse();
        assertThat(result.failedCases()).isEqualTo(1);
        assertThat(result.coverage().inputSchemaValidated()).isZero();
        assertThat(result.results().getFirst().diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message()).contains("requestedAmount"));
    }

    @Test
    void storedTransportFixtureUsesRealResponseProtocolAndProducesCertifiableEvidence() throws IOException {
        MapRegistry resources = new MapRegistry();
        resources.put(new ResourceDescriptor(
                "loan-applicant-service.getProfile",
                "https://unreachable.invalid/applicants/{applicantId}",
                "GET",
                Map.of(),
                null,
                Duration.ofSeconds(1),
                new ParameterMapping(Map.of("applicantId", "ctx.params.applicantId"), Map.of(), null),
                new ResponseProtocol.BodyCode("code", Set.of(0), "message"),
                "data"));
        GatewayGraphContractTestService service = testService("loan-decision-policy", resources);
        GatewayGraphContractTestCase testCase = new GatewayGraphContractTestCase(
                "transport prime applicant",
                Map.of("applicantId", "prime", "requestedAmount", 450_000.0),
                List.of(GatewayGraphResourceMock.transportResponse(
                        "loan-applicant-service.getProfile",
                        Map.of("applicantId", "prime"),
                        """
                                {"code":0,"message":"OK","data":{"applicantId":"prime","score":780,"segment":"private-bank"}}
                                """,
                        200,
                        Map.of("X-Test-Trace", "fixture-1"),
                        true)),
                "assembleLoanDecision",
                List.of(new GatewayGraphTestAssertion(
                        GatewayGraphTestAssertion.Mode.PATH_EQUALS,
                        "/policy/ruleId",
                        "R1")));
        GatewayGraphContractTestSuite stored = new GatewayGraphContractTestSuite(
                "transport-fixture", "Transport fixture", "", List.of("f3"),
                new GatewayGraphContractTestSuiteRequest("loanDecisionPolicy", List.of(testCase)),
                GatewayGraphContractTestCoveragePolicy.none());

        GatewayGraphContractTestSuiteResult certified = service.run(stored);
        GatewayGraphContractTestSuiteResult inline = service.run(stored.request());

        assertThat(certified.passed()).as("stored suite result: %s", certified).isTrue();
        assertThat(objectMapper.valueToTree(certified.results().getFirst().output())
                .at("/applicant/score").asInt()).isEqualTo(780);
        assertThat(certified.results().getFirst().evidence().evidenceClass())
                .isEqualTo(TestRunEvidence.EvidenceClass.CERTIFIABLE);
        assertThat(certified.results().getFirst().evidence().nodes())
                .filteredOn(node -> node.nodeId().equals("fetchApplicant"))
                .singleElement()
                .satisfies(node -> {
                    assertThat(node.operatorRef()).isEqualTo("httpResource");
                    assertThat(node.status()).isEqualTo("MOCKED");
                    assertThat(node.fidelity()).isEqualTo("TRANSPORT_LEVEL");
                });
        assertThat(inline.passed()).isTrue();
        assertThat(inline.results().getFirst().evidence().evidenceClass())
                .isEqualTo(TestRunEvidence.EvidenceClass.EXPLORATORY);
    }

    @Test
    void resourceMockJsonWithoutFidelityFieldsRemainsOutputLevelCompatible() throws Exception {
        GatewayGraphResourceMock mock = objectMapper.readValue("""
                {
                  "resourceId":"customer.get",
                  "expectedParams":{"customerId":"C-1"},
                  "payload":{"name":"Ada"},
                  "statusCode":200,
                  "rawBody":"",
                  "durationMs":0,
                  "success":true,
                  "required":true
                }
                """, GatewayGraphResourceMock.class);

        assertThat(mock.fixtureMode()).isEqualTo(GatewayGraphResourceMock.FixtureMode.OUTPUT_LEVEL);
        assertThat(mock.responseHeaders()).isEmpty();
        assertThat(mock.minUses()).isEqualTo(1);
        assertThat(mock.maxUses()).isEqualTo(1);
    }

    @Test
    void contractTestApiRunsTableSuites() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new GatewayGraphContractTestController(
                        testService("loan-decision-policy"),
                        new InMemoryGatewayGraphContractTestSuiteRepository(List.of())))
                .build();

        GatewayGraphContractTestSuiteRequest request = new GatewayGraphContractTestSuiteRequest(
                "loanDecisionPolicy",
                List.of(new GatewayGraphContractTestCase(
                        "prime applicant",
                        Map.of("applicantId", "prime", "requestedAmount", 450_000.0),
                        List.of(new GatewayGraphResourceMock(
                                "loan-applicant-service.getProfile",
                                Map.of("applicantId", "prime"),
                                Map.of("applicantId", "prime", "score", 780, "segment", "private-bank"))),
                        "assembleLoanDecision",
                        List.of(new GatewayGraphTestAssertion(
                                GatewayGraphTestAssertion.Mode.PATH_EQUALS,
                                "/policy/decision",
                                "approved")))));

        mockMvc.perform(post("/api/gateway/graphs/contracts/tests/run")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(GatewayGraphContractTestSuiteResult.SCHEMA_VERSION))
                .andExpect(jsonPath("$.graphName").value("loanDecisionPolicy"))
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.coverage.assertionCount").value(1))
                .andExpect(jsonPath("$.results[0].mockedResourceInvocations[0].resourceId")
                        .value("loan-applicant-service.getProfile"));
    }

    @Test
    void draftGeneratesEditableGraphMockSuiteFromFormalSchemas() throws IOException {
        GatewayGraphContractTestService service = testService("loan-decision-policy");

        GatewayGraphContractTestDraftResponse draft = service.draft(new GatewayGraphContractTestDraftRequest(
                GatewayGraphContractTestDraftRequest.SCHEMA_VERSION,
                "loanDecisionPolicy",
                "generated prime applicant",
                "assembleLoanDecision",
                Map.of("applicantId", "prime", "requestedAmount", 450_000.0),
                Map.of("loan-applicant-service.getProfile",
                        Map.of("applicantId", "prime", "score", 780, "segment", "private-bank"))));
        GatewayGraphContractTestSuiteResult result = service.run(draft.suite());

        assertThat(draft.diagnostics()).isEmpty();
        assertThat(draft.contract().inputSchema().schema().get("type")).isEqualTo("object");
        assertThat(draft.contract().outputSchema().schema().get("type")).isEqualTo("object");
        assertThat(draft.suite().cases().getFirst().context())
                .containsEntry("applicantId", "prime")
                .containsEntry("requestedAmount", 450_000.0);
        assertThat(draft.suite().cases().getFirst().resourceMocks().getFirst().expectedParams())
                .containsEntry("applicantId", "prime");
        assertThat(draft.suite().cases().getFirst().assertions().getFirst().mode())
                .isEqualTo(GatewayGraphTestAssertion.Mode.OUTPUT_MATCHES_SCHEMA);
        assertThat(result.passed()).as("draft contract result: %s", result).isTrue();
        assertThat(result.coverage().contractOutputSchemaValidated()).isEqualTo(1);
    }

    @Test
    void contractTestApiDraftsGraphMockSuites() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new GatewayGraphContractTestController(
                        testService("loan-decision-policy"),
                        new InMemoryGatewayGraphContractTestSuiteRepository(List.of())))
                .build();

        GatewayGraphContractTestDraftRequest request = new GatewayGraphContractTestDraftRequest(
                GatewayGraphContractTestDraftRequest.SCHEMA_VERSION,
                "loanDecisionPolicy",
                "generated prime applicant",
                "assembleLoanDecision",
                Map.of("applicantId", "prime", "requestedAmount", 450_000.0),
                Map.of("loan-applicant-service.getProfile",
                        Map.of("applicantId", "prime", "score", 780, "segment", "private-bank")));

        mockMvc.perform(post("/api/gateway/graphs/contracts/tests/draft")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(GatewayGraphContractTestDraftResponse.SCHEMA_VERSION))
                .andExpect(jsonPath("$.contract.schemaVersion").value(GatewayGraphContract.SCHEMA_VERSION))
                .andExpect(jsonPath("$.contract.inputSchema.schema.properties.applicantId.type").value("string"))
                .andExpect(jsonPath("$.contract.outputSchema.schema.properties.policy.type").value("object"))
                .andExpect(jsonPath("$.suite.graphName").value("loanDecisionPolicy"))
                .andExpect(jsonPath("$.suite.cases[0].context.applicantId").value("prime"))
                .andExpect(jsonPath("$.suite.cases[0].resourceMocks[0].resourceId")
                        .value("loan-applicant-service.getProfile"))
                .andExpect(jsonPath("$.suite.cases[0].resourceMocks[0].required").value(false))
                .andExpect(jsonPath("$.suite.cases[0].assertions[0].mode").value("OUTPUT_MATCHES_SCHEMA"));
    }

    @Test
    void storedSuiteRunEnforcesCoveragePolicy() throws IOException {
        GatewayGraphContractTestService service = testService("loan-decision-policy");

        GatewayGraphContractTestSuite suite = new GatewayGraphContractTestSuite(
                "under-covered-loan-policy",
                "Under-covered loan policy",
                "",
                List.of("policy"),
                new GatewayGraphContractTestSuiteRequest(
                        "loanDecisionPolicy",
                        List.of(new GatewayGraphContractTestCase(
                                "prime applicant",
                                Map.of("applicantId", "prime", "requestedAmount", 450_000.0),
                                List.of(new GatewayGraphResourceMock(
                                        "loan-applicant-service.getProfile",
                                        Map.of("applicantId", "prime"),
                                        Map.of("applicantId", "prime", "score", 780))),
                                "assembleLoanDecision",
                                List.of(new GatewayGraphTestAssertion(
                                        GatewayGraphTestAssertion.Mode.PATH_EQUALS,
                                        "/policy/ruleId",
                                        "R1"))))),
                new GatewayGraphContractTestCoveragePolicy(2, 2, 2, 2, 4, List.of("assembleLoanDecision")));

        GatewayGraphContractTestSuiteResult result = service.run(suite);

        assertThat(result.passed()).isFalse();
        assertThat(result.policyResult().passed()).isFalse();
        assertThat(result.policyResult().diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message()).contains("at least 2 cases"));
    }

    @Test
    void batchRunAggregatesStoredSuiteEvidence() throws IOException {
        GatewayGraphContractTestService service = testService("loan-decision-policy", loanResources());
        GatewayGraphContractTestSuiteRepository repository = loanRepository();

        GatewayGraphContractTestBatchResult result = service.runAll(repository.all());

        assertThat(result.passed()).isTrue();
        assertThat(result.totalSuites()).isEqualTo(1);
        assertThat(result.passedSuites()).isEqualTo(1);
        assertThat(result.totalCases()).isEqualTo(2);
        assertThat(result.coverage().contractOutputSchemaValidated()).isEqualTo(2);
        assertThat(result.coverage().mockedResourceCalls()).isEqualTo(2);
        assertThat(result.coverage().assertionCount()).isEqualTo(4);
    }

    @Test
    void contractTestApiListsRunsAndBatchesStoredSuites() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new GatewayGraphContractTestController(
                        testService("loan-decision-policy", loanResources()),
                        loanRepository()))
                .build();

        mockMvc.perform(get("/api/gateway/graphs/contracts/tests/suites"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion")
                        .value(GatewayGraphContractTestSuiteCatalogResponse.SCHEMA_VERSION))
                .andExpect(jsonPath("$.suites[0].suiteId").value("loan-decision-policy-smoke"))
                .andExpect(jsonPath("$.suites[0].caseCount").value(2));

        mockMvc.perform(post("/api/gateway/graphs/contracts/tests/suites/loan-decision-policy-smoke/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.policyResult.passed").value(true))
                .andExpect(jsonPath("$.coverage.assertionCount").value(4));

        mockMvc.perform(post("/api/gateway/graphs/contracts/tests/suites/run-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(GatewayGraphContractTestBatchResult.SCHEMA_VERSION))
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.totalSuites").value(1))
                .andExpect(jsonPath("$.coverage.mockedResourceCalls").value(2));
    }

    @Test
    void builtInSuiteCatalogCoversEveryGraphAndUsesExplicitTransportFixtures() {
        GatewayGraphContractTestSuiteRepository repository =
                new InMemoryGatewayGraphContractTestSuiteRepository();

        assertThat(repository.all()).hasSize(7);
        assertThat(repository.all())
                .extracting(suite -> suite.request().graphName())
                .containsExactlyInAnyOrder(
                        "aiEnrichedSearch",
                        "creditScore",
                        "enrichOrderList",
                        "loanDecisionPolicy",
                        "productDetail",
                        "resourceDispatch",
                        "userDashboard");
        assertThat(repository.all().stream()
                .flatMap(suite -> suite.request().cases().stream())
                .flatMap(testCase -> testCase.resourceMocks().stream()))
                .allSatisfy(mock -> assertThat(mock.fixtureMode())
                        .isEqualTo(GatewayGraphResourceMock.FixtureMode.TRANSPORT_LEVEL));
        assertThat(repository.find("enrich-order-list-outer-boundary").orElseThrow().tags())
                .contains("exploratory", "nested-invocation-gap");
        assertThat(repository.find("credit-score-provider-routing").orElseThrow()
                .request().cases().get(1).resourceMocks().getFirst())
                .satisfies(mock -> {
                    assertThat(mock.minUses()).isEqualTo(2);
                    assertThat(mock.maxUses()).isEqualTo(2);
                });
    }

    private static GatewayGraphContractTestService testService(String resourceName) throws IOException {
        return testService(resourceName, null);
    }

    private static GatewayGraphContractTestService testService(String resourceName,
                                                               ResourceRegistry resources) throws IOException {
        var registry = new DefaultOperatorRegistry();
        registry.registerRaw("httpResource", new NoopOperator());
        var loader = new GraphLoader(registry);
        String dsl = Files.readString(Path.of("src/main/resources/bloge/gateway/" + resourceName + ".bloge"));
        Graph graph = loader.load(dsl);
        GatewayGraphService graphService = new GatewayGraphService(
                GraphEngine.builder().registry(registry).build(),
                List.of(graph),
                GatewayGraphContractCatalog.builtIn());
        InMemoryResourceDesignContractRegistry resourceContracts = new InMemoryResourceDesignContractRegistry();
        new ResourceDesignContractBootstrap(resourceContracts).seedContracts();
        return new GatewayGraphContractTestService(
                graphService,
                new ObjectMapper(),
                new JsonSchemaSampleGenerator(),
                resourceContracts,
                resources,
                resources == null ? null : new BlgeExpressionEvaluator());
    }

    private static GatewayGraphContractTestSuiteRepository loanRepository() {
        GatewayGraphContractTestSuite loan = new InMemoryGatewayGraphContractTestSuiteRepository()
                .find("loan-decision-policy-smoke")
                .orElseThrow();
        return new InMemoryGatewayGraphContractTestSuiteRepository(List.of(loan));
    }

    private static ResourceRegistry loanResources() {
        MapRegistry resources = new MapRegistry();
        resources.put(new ResourceDescriptor(
                "loan-applicant-service.getProfile",
                "https://unreachable.invalid/applicants/{applicantId}",
                "GET",
                Map.of(),
                null,
                Duration.ofSeconds(1),
                new ParameterMapping(Map.of("applicantId", "ctx.params.applicantId"), Map.of(), null),
                new ResponseProtocol.BodyCode("code", Set.of(0, "0", "SUCCESS"), "message"),
                "data"));
        return resources;
    }

    private static class NoopOperator implements Operator<Object, Object> {
        @Override
        public Object execute(Object input, OperatorContext ctx) {
            return Map.of();
        }
    }

    private static final class MapRegistry implements ResourceRegistry {
        private final Map<String, ResourceDescriptor> descriptors = new LinkedHashMap<>();

        void put(ResourceDescriptor descriptor) {
            descriptors.put(descriptor.resourceId(), descriptor);
        }

        @Override
        public ResourceDescriptor resolve(String resourceId) {
            ResourceDescriptor descriptor = descriptors.get(resourceId);
            if (descriptor == null) {
                throw new IllegalArgumentException("Unknown resource: " + resourceId);
            }
            return descriptor;
        }

        @Override
        public boolean contains(String resourceId) {
            return descriptors.containsKey(resourceId);
        }

        @Override
        public Collection<ResourceDescriptor> all() {
            return List.copyOf(descriptors.values());
        }
    }
}
