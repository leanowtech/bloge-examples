package com.leanowtech.bloge.gateway.solution.journey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.agenttdd.InMemoryAgentTddStateRepository;
import com.leanowtech.bloge.gateway.solution.FeatureContract;
import com.leanowtech.bloge.gateway.solution.InstructionContract;
import com.leanowtech.bloge.gateway.solution.ScenarioContract;
import com.leanowtech.bloge.gateway.solution.SolutionContract;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies business fixtures compile to deterministic, Solution-scoped IoC plans. */
class BusinessFixtureCompilerTest {
    private static final String SCOPE = "tenant-a|org-a|project-a|test|sg";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final SolutionEntityRegistry registry = new SolutionEntityRegistry(
            new InMemoryAgentTddStateRepository(), mapper);
    private final BusinessFixtureCompiler compiler = new BusinessFixtureCompiler(registry, mapper);

    @BeforeEach
    void defineReachableCapabilities() {
        registry.upsertFeature(SCOPE, new FeatureContract(
                "feature:party", mapper.valueToTree(Map.of("type", "string")),
                FeatureContract.EvaluationKind.API, FeatureContract.Determinism.DETERMINISTIC,
                mapper.valueToTree(Map.of("orderId", "string")), "resource:party", "", "", "取消责任方"));
        registry.upsertFeature(SCOPE, new FeatureContract(
                "feature:order", mapper.valueToTree(Map.of("type", "string")),
                FeatureContract.EvaluationKind.API, FeatureContract.Determinism.DETERMINISTIC,
                mapper.valueToTree(Map.of("orderId", "string")), "resource:order", "", "", "订单编号"));
        registry.upsertInstruction(SCOPE, instruction("ins:refund", "退款执行"));
        registry.upsertInstruction(SCOPE, readInstruction("ins:balance", "余额查询"));
        registry.upsertScenario(SCOPE, scenario(List.of("ins:refund", "ins:balance")));
        registry.upsertSolution(SCOPE, solution(List.of("ins:refund", "ins:balance")), false);
    }

    @Test
    void compilesEveryControlledOutcomeWithIndependentPlanFingerprints() {
        for (String outcome : List.of("RETURNS", "UNAVAILABLE", "SUCCEEDS_WITHOUT_EFFECT",
                "FAILS_WITHOUT_EFFECT", "MUST_NOT_BE_USED")) {
            BusinessFixtureCompiler.ControlledAssumptionPlan plan = compiler.compile(
                    SCOPE, registry.requireSolution(SCOPE, "sol:cancel"),
                    fixture(outcome, "RETURNS".equals(outcome) ? "余额查询" : "退款执行"));

            assertThat(plan.given().path("party").asText()).isEqualTo("passenger");
            String expectedRef = "RETURNS".equals(outcome) ? "ins:balance" : "ins:refund";
            assertThat(plan.dependencyAssumptions().path(expectedRef).path("outcome").asText())
                    .isEqualTo(outcome);
            assertThat(plan.businessContractVector()).hasSize(2);
            assertThat(plan.businessContractVector().getLast().path("semanticKey").asText())
                    .startsWith("legacy:");
            assertThat(plan.featureValuesFingerprint()).startsWith("sha256:");
            assertThat(plan.dependencyPlanFingerprint()).startsWith("sha256:");
            assertThat(plan.planFingerprint()).startsWith("sha256:");
        }
    }

    @Test
    void resolvesAnInstructionReferencedOnlyByTheReachableScenario() {
        registry.upsertInstruction(SCOPE, instruction("ins:scenario-only", "转人工复核"));
        registry.upsertScenario(SCOPE, scenario(List.of("ins:scenario-only")));
        registry.upsertSolution(SCOPE, solution(List.of("ins:refund")), false);
        JsonNode fixture = fixture("SUCCEEDS_WITHOUT_EFFECT").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) fixture.at("/dependencyAssumptions/0"))
                .put("capabilityName", "转人工复核");

        BusinessFixtureCompiler.ControlledAssumptionPlan plan = compiler.compile(
                SCOPE, registry.requireSolution(SCOPE, "sol:cancel"), fixture);

        assertThat(plan.dependencyAssumptions().has("ins:scenario-only")).isTrue();
    }

    @Test
    void failsClosedWhenOneBusinessNameResolvesToTwoReachableInstructions() {
        registry.upsertInstruction(SCOPE, instruction("ins:refund-duplicate", "退款执行"));
        registry.upsertScenario(SCOPE, scenario(List.of("ins:refund", "ins:refund-duplicate")));
        registry.upsertSolution(SCOPE, solution(List.of("ins:refund", "ins:refund-duplicate")), false);

        assertThatThrownBy(() -> compiler.compile(
                SCOPE, registry.requireSolution(SCOPE, "sol:cancel"),
                fixture("SUCCEEDS_WITHOUT_EFFECT")))
                .isInstanceOf(AgentTddToolException.class)
                .extracting(failure -> ((AgentTddToolException) failure).code())
                .isEqualTo("BUSINESS_ASSUMPTION_AMBIGUOUS");
    }

    @Test
    void rejectsReturnedFactSemanticsForAWriteCapability() {
        assertThatThrownBy(() -> compiler.compile(
                SCOPE, registry.requireSolution(SCOPE, "sol:cancel"), fixture("RETURNS")))
                .isInstanceOf(AgentTddToolException.class)
                .extracting(failure -> ((AgentTddToolException) failure).code())
                .isEqualTo("BUSINESS_ASSUMPTION_EFFECT_INVALID");
    }

    private InstructionContract instruction(String ref, String semantics) {
        return new InstructionContract(ref, mapper.valueToTree(Map.of("orderId", "string")),
                mapper.valueToTree(Map.of("result", Map.of("type", "string"),
                        "reasoning", "required")), InstructionContract.Effect.WRITE,
                "operator:" + ref, new InstructionContract.WriteGovernance(
                        "refund-service", "orderId", "recon:refund"), semantics);
    }

    private InstructionContract readInstruction(String ref, String semantics) {
        return new InstructionContract(ref, mapper.valueToTree(Map.of("orderId", "string")),
                mapper.valueToTree(Map.of("result", Map.of("type", "string"),
                        "reasoning", "required")), InstructionContract.Effect.READ,
                "operator:" + ref, null, semantics);
    }

    private ScenarioContract scenario(List<String> instructions) {
        List<ScenarioContract.Rule> rules = instructions.stream().map(ref -> new ScenarioContract.Rule(
                "rule:" + ref, mapper.valueToTree(Map.of("party", Map.of("eq", "passenger"))),
                new ScenarioContract.Outlet(ScenarioContract.OutletKind.INSTRUCTION,
                        ref, Map.of("orderId", "orderId"), ""))).toList();
        return new ScenarioContract("scn:cancel", List.of("party"),
                ScenarioContract.HitPolicy.UNIQUE, rules,
                new ScenarioContract.Outlet(ScenarioContract.OutletKind.TERMINAL,
                        "", Map.of(), "MANUAL_REVIEW"));
    }

    private SolutionContract solution(List<String> instructions) {
        return new SolutionContract("sol:cancel", "处理取消费争议",
                Map.of("party", "feature:party", "orderId", "feature:order"),
                "scn:cancel", instructions, "caseSet:cancel");
    }

    private JsonNode fixture(String outcome) {
        return fixture(outcome, "退款执行");
    }

    private JsonNode fixture(String outcome, String capabilityName) {
        Map<String, Object> dependency = new java.util.LinkedHashMap<>();
        dependency.put("capabilityName", capabilityName);
        dependency.put("outcome", outcome);
        if ("RETURNS".equals(outcome)) dependency.put("value", Map.of(
                "result", Map.of("decision", "WAIVED"), "reasoning", "符合政策"));
        return mapper.valueToTree(Map.of(
                "givenFacts", List.of(Map.of("factName", "取消责任方", "value", "passenger")),
                "dependencyAssumptions", List.of(dependency)));
    }
}
