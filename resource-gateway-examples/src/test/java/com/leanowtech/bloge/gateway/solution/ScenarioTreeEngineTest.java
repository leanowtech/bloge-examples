package com.leanowtech.bloge.gateway.solution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.agenttdd.InMemoryAgentTddStateRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Proves bounded, pure and deterministic Scenario tree validation and evaluation. */
class ScenarioTreeEngineTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final SolutionEntityRegistry registry = new SolutionEntityRegistry(
            new InMemoryAgentTddStateRepository(), mapper);

    @Test
    void evaluatesNestedScenarioToOneInstructionWithResolvedBindings() {
        instruction("ins:refund", List.of("orderId"));
        scenario(new ScenarioContract("scn:child", List.of("party"),
                ScenarioContract.HitPolicy.UNIQUE,
                List.of(rule("C1", Map.of("party", Map.of("eq", "driver")),
                        instructionOutlet("ins:refund", Map.of("orderId", "orderId")))),
                terminal("NO_ACTION")));
        scenario(new ScenarioContract("scn:root", List.of("party"),
                ScenarioContract.HitPolicy.UNIQUE,
                List.of(rule("R4", Map.of("party", Map.of("eq", "driver")),
                        scenarioOutlet("scn:child", Map.of("party", "party")))),
                terminal("ESCALATE")));

        ScenarioTreeValidator.ValidationResult validation =
                new ScenarioTreeValidator(registry, 8).validate(SCOPE, "scn:root");
        ScenarioTreeEvaluator.Outcome outcome = new ScenarioTreeEvaluator(registry, 8)
                .evaluate(SCOPE, "scn:root", mapper.valueToTree(Map.of(
                        "party", "driver", "orderId", "O-1")));

        assertThat(validation.acyclic()).isTrue();
        assertThat(validation.maxDepth()).isEqualTo(2);
        assertThat(outcome.outletKind()).isEqualTo("INSTRUCTION");
        assertThat(outcome.ref()).isEqualTo("ins:refund");
        assertThat(outcome.rulePath()).containsExactly("R4", "C1");
        assertThat(outcome.bind()).containsEntry("orderId", "O-1");
    }

    @Test
    void rejectsCycleBeforeAnyScenarioCanRun() {
        scenario(new ScenarioContract("scn:a", List.of("x"), ScenarioContract.HitPolicy.UNIQUE,
                List.of(rule("A1", Map.of("x", Map.of("eq", 1)),
                        scenarioOutlet("scn:b", Map.of("x", "x")))), terminal("END")));
        scenario(new ScenarioContract("scn:b", List.of("x"), ScenarioContract.HitPolicy.UNIQUE,
                List.of(rule("B1", Map.of("x", Map.of("eq", 1)),
                        scenarioOutlet("scn:a", Map.of("x", "x")))), terminal("END")));

        assertThatThrownBy(() -> new ScenarioTreeValidator(registry, 8).validate(SCOPE, "scn:a"))
                .isInstanceOf(SolutionContractException.class)
                .extracting(failure -> ((SolutionContractException) failure).code())
                .isEqualTo("SCENARIO_TREE_CYCLE");
    }

    @Test
    void rejectsIncompleteTargetBindingsAndDepthOverflow() {
        instruction("ins:write", List.of("orderId", "amount"));
        scenario(new ScenarioContract("scn:bad-bind", List.of("x"), ScenarioContract.HitPolicy.UNIQUE,
                List.of(rule("R1", Map.of("x", Map.of("eq", 1)),
                        instructionOutlet("ins:write", Map.of("orderId", "orderId")))),
                terminal("END")));
        scenario(new ScenarioContract("scn:leaf", List.of("x"), ScenarioContract.HitPolicy.UNIQUE,
                List.of(rule("L1", Map.of("x", Map.of("eq", 1)), terminal("END"))), terminal("END")));
        scenario(new ScenarioContract("scn:deep", List.of("x"), ScenarioContract.HitPolicy.UNIQUE,
                List.of(rule("D1", Map.of("x", Map.of("eq", 1)),
                        scenarioOutlet("scn:leaf", Map.of("x", "x")))), terminal("END")));

        assertThatThrownBy(() -> new ScenarioTreeValidator(registry, 8)
                .validate(SCOPE, "scn:bad-bind"))
                .isInstanceOf(SolutionContractException.class)
                .extracting(failure -> ((SolutionContractException) failure).code())
                .isEqualTo("SCENARIO_BIND_INCOMPLETE");
        assertThatThrownBy(() -> new ScenarioTreeValidator(registry, 1)
                .validate(SCOPE, "scn:deep"))
                .isInstanceOf(SolutionContractException.class)
                .extracting(failure -> ((SolutionContractException) failure).code())
                .isEqualTo("SCENARIO_TREE_TOO_DEEP");
    }

    private void scenario(ScenarioContract contract) {
        registry.upsertScenario(SCOPE, contract);
    }

    private void instruction(String ref, List<String> inputs) {
        Map<String, String> inputSchema = new java.util.LinkedHashMap<>();
        inputs.forEach(input -> inputSchema.put(input, "string"));
        registry.upsertInstruction(SCOPE, new InstructionContract(
                ref, mapper.valueToTree(inputSchema),
                mapper.valueToTree(Map.of("result", Map.of("type", "object"), "reasoning", "required")),
                InstructionContract.Effect.READ, "tool:" + ref, null));
    }

    private ScenarioContract.Rule rule(
            String id, Map<String, Object> when, ScenarioContract.Outlet outlet) {
        return new ScenarioContract.Rule(id, mapper.valueToTree(when), outlet);
    }

    private static ScenarioContract.Outlet instructionOutlet(String ref, Map<String, String> bind) {
        return new ScenarioContract.Outlet(
                ScenarioContract.OutletKind.INSTRUCTION, ref, bind, "");
    }

    private static ScenarioContract.Outlet scenarioOutlet(String ref, Map<String, String> bind) {
        return new ScenarioContract.Outlet(
                ScenarioContract.OutletKind.SUB_SCENARIO, ref, bind, "");
    }

    private static ScenarioContract.Outlet terminal(String kind) {
        return new ScenarioContract.Outlet(
                ScenarioContract.OutletKind.TERMINAL, "", Map.of(), kind);
    }

    private static final String SCOPE = "tenant-a/project-a/test";
}
