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
                .standaloneSetup(new GatewayGraphContractTestController(testService("loan-decision-policy")))
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
