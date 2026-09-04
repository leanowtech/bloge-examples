package com.leanowtech.bloge.gateway.solution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.context.TenantContext;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.gateway.agenttdd.InMemoryAgentTddStateRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves solution built-ins are pure for decisions and stub writes during simulation. */
class SolutionCallOperatorTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final SolutionEntityRegistry registry = new SolutionEntityRegistry(
            new InMemoryAgentTddStateRepository(), mapper);

    @Test
    void scenarioCallEvaluatesOnlySuppliedFeatureValues() throws Exception {
        registry.upsertInstruction(SCOPE, readInstruction());
        registry.upsertScenario(SCOPE, new ScenarioContract(
                "scn:root", List.of("party"), ScenarioContract.HitPolicy.UNIQUE,
                List.of(new ScenarioContract.Rule("R1",
                        mapper.valueToTree(Map.of("party", Map.of("eq", "none"))),
                        new ScenarioContract.Outlet(ScenarioContract.OutletKind.INSTRUCTION,
                                "ins:uphold", Map.of("orderId", "orderId"), ""))),
                new ScenarioContract.Outlet(
                        ScenarioContract.OutletKind.TERMINAL, "", Map.of(), "ESCALATE")));
        GraphContext graph = authorized(SolutionExecutionAuthority.Mode.SIMULATE);

        Map<String, Object> output = new ScenarioCallOperator(registry).execute(Map.of(
                "scenarioRef", "scn:root",
                "values", Map.of("party", "none", "orderId", "O-1")),
                new OperatorContext("decide", "solution", graph, 0));

        assertThat(output).containsEntry("outletKind", "INSTRUCTION")
                .containsEntry("ref", "ins:uphold");
        assertThat(((Map<?, ?>) output.get("bind")).get("orderId")).isEqualTo("O-1");
    }

    @Test
    void instructionCallStubsWriteDuringSimulationAndDelegatesRead() throws Exception {
        registry.upsertInstruction(SCOPE, readInstruction());
        registry.upsertInstruction(SCOPE, writeInstruction());
        AtomicInteger delegated = new AtomicInteger();
        InstructionDispatchChannel channel = (instruction, values, context) -> {
            delegated.incrementAndGet();
            return Map.of("result", Map.of("decision", "UPHELD"), "reasoning", "rule R3");
        };
        InstructionCallOperator operator = new InstructionCallOperator(registry, channel);

        Map<String, Object> simulatedWrite = operator.execute(Map.of(
                        "instructionRef", "ins:refund", "values", Map.of("orderId", "O-1")),
                new OperatorContext("dispatch", "solution",
                        authorized(SolutionExecutionAuthority.Mode.SIMULATE), 0));
        Map<String, Object> read = operator.execute(Map.of(
                        "instructionRef", "ins:uphold", "values", Map.of("orderId", "O-1")),
                new OperatorContext("dispatch", "solution",
                        authorized(SolutionExecutionAuthority.Mode.RUNTIME), 0));

        assertThat(simulatedWrite).containsEntry("reasoning", "SIMULATED_WRITE_STUB");
        assertThat(delegated).hasValue(1);
        assertThat(read).containsKey("result").containsEntry("reasoning", "rule R3");
    }

    private GraphContext authorized(SolutionExecutionAuthority.Mode mode) {
        GraphContext context = new GraphContext(new TenantContext("tenant-a", "project-a"));
        context.put(SolutionExecutionAuthority.CONTEXT_KEY,
                SolutionExecutionAuthority.issue(SCOPE, mode));
        return context;
    }

    private InstructionContract readInstruction() {
        return new InstructionContract(
                "ins:uphold", mapper.valueToTree(Map.of("orderId", "string")),
                mapper.valueToTree(Map.of("result", Map.of("type", "object"), "reasoning", "required")),
                InstructionContract.Effect.READ, "tool:uphold", null);
    }

    private InstructionContract writeInstruction() {
        return new InstructionContract(
                "ins:refund", mapper.valueToTree(Map.of("orderId", "string")),
                mapper.valueToTree(Map.of("result", Map.of("type", "object"), "reasoning", "required")),
                InstructionContract.Effect.WRITE, "",
                new InstructionContract.WriteGovernance("refund", "orderId", "recon:refund"));
    }

    private static final String SCOPE = "tenant-a|org-a|project-a|test|sg";
}
