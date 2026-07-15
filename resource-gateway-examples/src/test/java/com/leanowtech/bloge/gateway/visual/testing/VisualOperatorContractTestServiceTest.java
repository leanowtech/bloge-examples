package com.leanowtech.bloge.gateway.visual.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.simulation.JsonSchemaSampleGenerator;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VisualOperatorContractTestServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void runsSchemaGatedOperatorMockRows() {
        VisualOperatorContractTestService service = testService();

        VisualOperatorContractTestSuiteResult result = service.run(new VisualOperatorContractTestSuiteRequest(
                "risk:eligibility",
                List.of(new VisualOperatorContractTestCase(
                        "eligible applicant",
                        Map.of("inputs", Map.of("score", 720, "amount", 250_000.0)),
                        Map.of(),
                        Map.of("output", Map.of("eligible", true, "ruleId", "ELIGIBILITY_V1")),
                        Map.of("output", List.of(
                                new VisualOperatorTestAssertion(
                                        VisualOperatorTestAssertion.Mode.PATH_EQUALS,
                                        "/eligible",
                                        true),
                                new VisualOperatorTestAssertion(
                                        VisualOperatorTestAssertion.Mode.PATH_EXISTS,
                                        "/ruleId",
                                        null)))))));

        assertThat(result.passed()).isTrue();
        assertThat(result.mode()).isEqualTo(VisualOperatorContractTestSuiteResult.Mode.SCHEMA_CONTRACT);
        assertThat(result.totalCases()).isEqualTo(1);
        assertThat(result.coverage().inputPortSchemaValidated()).isEqualTo(1);
        assertThat(result.coverage().configSchemaValidated()).isEqualTo(1);
        assertThat(result.coverage().mockedOutputSchemaValidated()).isEqualTo(1);
        assertThat(result.coverage().mockedOutputCount()).isEqualTo(1);
        assertThat(result.coverage().assertionCount()).isEqualTo(2);
        assertThat(result.results().getFirst().diagnostics()).isEmpty();
    }

    @Test
    void failsWhenOperatorMockRowViolatesInputOrOutputSchema() {
        VisualOperatorContractTestService service = testService();

        VisualOperatorContractTestSuiteResult result = service.run(new VisualOperatorContractTestSuiteRequest(
                "risk:eligibility",
                List.of(new VisualOperatorContractTestCase(
                        "bad applicant",
                        Map.of("inputs", Map.of("score", "high", "amount", 250_000.0)),
                        Map.of(),
                        Map.of("output", Map.of("eligible", "yes")),
                        Map.of("output", List.of(new VisualOperatorTestAssertion(
                                VisualOperatorTestAssertion.Mode.PATH_EQUALS,
                                "/eligible",
                                true)))))));

        assertThat(result.passed()).isFalse();
        assertThat(result.failedCases()).isEqualTo(1);
        assertThat(result.results().getFirst().diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.target()).contains("/inputs/inputs/score"))
                .anySatisfy(diagnostic -> assertThat(diagnostic.target()).contains("/mockedOutputs/output/eligible"))
                .anySatisfy(diagnostic -> assertThat(diagnostic.code())
                        .isEqualTo("visual.operatorContractTest.assertionFailed"));
    }

    @Test
    void failsWhenOutputSchemaAssertionIsNotAValidVisualSchema() {
        VisualOperatorContractTestService service = testService();

        VisualOperatorContractTestSuiteResult result = service.run(new VisualOperatorContractTestSuiteRequest(
                "risk:eligibility",
                List.of(new VisualOperatorContractTestCase(
                        "bad schema assertion",
                        Map.of("inputs", Map.of("score", 720, "amount", 250_000.0)),
                        Map.of(),
                        Map.of("output", Map.of("eligible", true, "ruleId", "ELIGIBILITY_V1")),
                        Map.of("output", List.of(new VisualOperatorTestAssertion(
                                VisualOperatorTestAssertion.Mode.OUTPUT_MATCHES_SCHEMA,
                                "",
                                Map.of("type", "spaceship"))))))));

        assertThat(result.passed()).isFalse();
        assertThat(result.results().getFirst().diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.code())
                        .isEqualTo("visual.schema.unsupportedType"));
    }

    @Test
    void operatorContractTestApiRunsAndDraftsSchemaMockSuites() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new VisualOperatorContractTestController(
                        testService(),
                        new InMemoryVisualOperatorContractTestSuiteRepository()))
                .build();

        VisualOperatorContractTestSuiteRequest request = new VisualOperatorContractTestSuiteRequest(
                "risk:eligibility",
                List.of(new VisualOperatorContractTestCase(
                        "eligible applicant",
                        Map.of("inputs", Map.of("score", 720, "amount", 250_000.0)),
                        Map.of(),
                        Map.of("output", Map.of("eligible", true, "ruleId", "ELIGIBILITY_V1")),
                        Map.of("output", List.of(new VisualOperatorTestAssertion(
                                VisualOperatorTestAssertion.Mode.PATH_EQUALS,
                                "/ruleId",
                                "ELIGIBILITY_V1"))))));

        mockMvc.perform(post("/api/visual/operators/tests/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(VisualOperatorContractTestSuiteResult.SCHEMA_VERSION))
                .andExpect(jsonPath("$.mode").value("SCHEMA_CONTRACT"))
                .andExpect(jsonPath("$.operatorRef").value("risk:eligibility"))
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.coverage.mockedOutputSchemaValidated").value(1));

        VisualOperatorContractTestDraftRequest draftRequest = new VisualOperatorContractTestDraftRequest(
                VisualOperatorContractTestDraftRequest.SCHEMA_VERSION,
                "risk:eligibility",
                "generated eligibility mock",
                true,
                Map.of("inputs", Map.of("score", 720, "amount", 250_000.0)),
                Map.of(),
                Map.of());

        mockMvc.perform(post("/api/visual/operators/tests/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(draftRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(VisualOperatorContractTestDraftResponse.SCHEMA_VERSION))
                .andExpect(jsonPath("$.operatorRef").value("risk:eligibility"))
                .andExpect(jsonPath("$.suite.operatorRef").value("risk:eligibility"))
                .andExpect(jsonPath("$.suite.cases[0].inputs.inputs.score").value(720))
                .andExpect(jsonPath("$.suite.cases[0].mockedOutputs.output.eligible").value(false))
                .andExpect(jsonPath("$.suite.cases[0].outputAssertions.output[0].mode")
                        .value("OUTPUT_MATCHES_SCHEMA"));
    }

    @Test
    void storedOperatorSuitesCanBeListedRunAndBatched() throws Exception {
        VisualOperatorContractTestSuite suite = new VisualOperatorContractTestSuite(
                "risk-eligibility-smoke",
                "Risk eligibility smoke",
                "Validates eligibility operator schema mocks.",
                List.of("risk", "operator"),
                new VisualOperatorContractTestSuiteRequest(
                        "risk:eligibility",
                        List.of(new VisualOperatorContractTestCase(
                                "eligible applicant",
                                Map.of("inputs", Map.of("score", 720, "amount", 250_000.0)),
                                Map.of(),
                                Map.of("output", Map.of("eligible", true, "ruleId", "ELIGIBILITY_V1")),
                                Map.of("output", List.of(new VisualOperatorTestAssertion(
                                        VisualOperatorTestAssertion.Mode.PATH_EQUALS,
                                        "/ruleId",
                                        "ELIGIBILITY_V1")))))));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new VisualOperatorContractTestController(
                        testService(),
                        new InMemoryVisualOperatorContractTestSuiteRepository(List.of(suite))))
                .build();

        mockMvc.perform(get("/api/visual/operators/tests/suites"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion")
                        .value(VisualOperatorContractTestSuiteCatalogResponse.SCHEMA_VERSION))
                .andExpect(jsonPath("$.suites[0].suiteId").value("risk-eligibility-smoke"))
                .andExpect(jsonPath("$.suites[0].caseCount").value(1));

        mockMvc.perform(post("/api/visual/operators/tests/suites/risk-eligibility-smoke/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.coverage.mockedOutputSchemaValidated").value(1));

        mockMvc.perform(post("/api/visual/operators/tests/suites/run-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(VisualOperatorContractTestBatchResult.SCHEMA_VERSION))
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.totalSuites").value(1))
                .andExpect(jsonPath("$.coverage.assertionCount").value(1));
    }

    private static VisualOperatorContractTestService testService() {
        return new VisualOperatorContractTestService(
                VisualCatalogTestSupport.catalogWithLibrary(VisualCatalogTestSupport.eligibilityLibrary("integer")),
                new JsonSchemaSampleGenerator(),
                new ObjectMapper());
    }
}
