package com.leanowtech.bloge.gateway.solution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.agenttdd.InMemoryAgentTddStateRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the two-operator pure Solution path with a zero-egress WRITE simulation. */
class SolutionExecutionServiceTest {

    @Test
    void simulatesACompleteSolutionWithoutCallingWriteChannel() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        SolutionEntityRegistry registry = new SolutionEntityRegistry(
                new InMemoryAgentTddStateRepository(), mapper);
        registry.upsertInstruction(SCOPE, new InstructionContract(
                "ins:refund", mapper.valueToTree(Map.of("orderId", "string")),
                mapper.valueToTree(Map.of(
                        "result", Map.of("type", "object"), "reasoning", "required")),
                InstructionContract.Effect.WRITE, "",
                new InstructionContract.WriteGovernance("refund", "orderId", "recon:refund")));
        registry.upsertScenario(SCOPE, new ScenarioContract(
                "scn:root", List.of("party"), ScenarioContract.HitPolicy.UNIQUE,
                List.of(new ScenarioContract.Rule("R1",
                        mapper.valueToTree(Map.of("party", Map.of("eq", "none"))),
                        new ScenarioContract.Outlet(ScenarioContract.OutletKind.INSTRUCTION,
                                "ins:refund", Map.of("orderId", "orderId"), ""))),
                new ScenarioContract.Outlet(
                        ScenarioContract.OutletKind.TERMINAL, "", Map.of(), "ESCALATE")));
        registry.upsertSolution(SCOPE, new SolutionContract(
                "sol:cancel", "Resolve cancellation fee disputes.",
                Map.of("party", "responsibility.party"), "scn:root",
                List.of("ins:refund"), "caseSet:cancel"), true);
        AtomicInteger realWrites = new AtomicInteger();
        InstructionDispatchChannel channel = (instruction, values, context) -> {
            realWrites.incrementAndGet();
            return Map.of("result", Map.of("decision", "WAIVED"), "reasoning", "rule R1");
        };

        SolutionExecutionService.ExecutionResult result = new SolutionExecutionService(
                registry, mapper, channel).simulate(SCOPE, "sol:cancel",
                mapper.valueToTree(Map.of("party", "none", "orderId", "O-1")));

        assertThat(result.instructionRef()).isEqualTo("ins:refund");
        assertThat(result.reasoning()).isEqualTo("SIMULATED_WRITE_STUB");
        assertThat(result.rulePath()).containsExactly("R1");
        assertThat(result.realExternalCalls()).isZero();
        assertThat(realWrites).hasValue(0);
    }

    private static final String SCOPE = "tenant-a|org-a|project-a|test|sg";
}
