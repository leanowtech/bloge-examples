package com.leanowtech.bloge.gateway.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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

        assertThat(result.passed()).isTrue();
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
        assertThat(result.results().getFirst().diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message()).contains("requestedAmount"));
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
        GatewayGraphContractTestService service = testService("loan-decision-policy");
        GatewayGraphContractTestSuiteRepository repository = new InMemoryGatewayGraphContractTestSuiteRepository();

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
                        testService("loan-decision-policy"),
                        new InMemoryGatewayGraphContractTestSuiteRepository()))
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

    private static GatewayGraphContractTestService testService(String resourceName) throws IOException {
        var registry = new DefaultOperatorRegistry();
        registry.registerRaw("httpResource", new NoopOperator());
        var loader = new GraphLoader(registry);
        String dsl = Files.readString(Path.of("src/main/resources/bloge/gateway/" + resourceName + ".bloge"));
        Graph graph = loader.load(dsl);
        GatewayGraphService graphService = new GatewayGraphService(
                GraphEngine.builder().registry(registry).build(),
                List.of(graph),
                GatewayGraphContractCatalog.builtIn());
        return new GatewayGraphContractTestService(graphService, new ObjectMapper());
    }

    private static class NoopOperator implements Operator<Object, Object> {
        @Override
        public Object execute(Object input, OperatorContext ctx) {
            return Map.of();
        }
    }
}
