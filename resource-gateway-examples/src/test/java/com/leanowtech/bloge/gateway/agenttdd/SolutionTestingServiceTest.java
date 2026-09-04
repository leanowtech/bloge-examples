package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.solution.InstructionContract;
import com.leanowtech.bloge.gateway.solution.ScenarioContract;
import com.leanowtech.bloge.gateway.solution.SolutionContract;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies Scenario contracts and approved Solution GOLDEN baselines remain pure and zero-egress. */
class SolutionTestingServiceTest {
    private static final String SCOPE = "tenant-a|org-a|project-a|test|sg";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
    private final SolutionEntityRegistry registry = new SolutionEntityRegistry(states, mapper);
    private final AtomicInteger writes = new AtomicInteger();
    private final SolutionTestingService testing = new SolutionTestingService(
            states, registry, mapper, (instruction, values, context) -> {
                writes.incrementAndGet();
                return Map.of("result", Map.of("decision", "REAL"), "reasoning", "real");
            });

    @BeforeEach
    void defineTree() {
        registry.upsertInstruction(SCOPE, new InstructionContract(
                "ins:refund", mapper.valueToTree(Map.of("orderId", "string")),
                mapper.valueToTree(Map.of(
                        "result", Map.of("type", Map.of("fields", Map.of(
                                "decision", Map.of("enum", List.of("WAIVED"))))),
                        "reasoning", "required")),
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
                "sol:cancel", "Resolve cancellation dispute.",
                Map.of("party", "responsibility.party", "orderId", "dispute.orderSelected"),
                "scn:root", List.of("ins:refund"), "caseSet:cancel"), true);
    }

    @Test
    void testsScenarioOutletsWithoutCallingAnyInstruction() {
        Map<String, Object> result = testing.testScenario(SCOPE, "scn:root", mapper.valueToTree(List.of(
                Map.of("caseId", "none-party", "given", Map.of("party", "none", "orderId", "O-1"),
                        "expect", Map.of("outletKind", "INSTRUCTION", "ref", "ins:refund")),
                Map.of("caseId", "fallback", "given", Map.of("party", "driver", "orderId", "O-2"),
                        "expect", Map.of("outletKind", "TERMINAL", "terminalKind", "ESCALATE")))));

        assertThat(result).containsEntry("passed", 2L).containsEntry("failed", 0L)
                .containsEntry("realExternalCalls", 0);
        assertThat(writes).hasValue(0);
    }

    @Test
    void runsOnlyApprovedGoldenAndSynthesizesContractShapedWriteResult() {
        storeCases("ACTIVE", Map.of("result", Map.of("decision", "WAIVED")));

        Map<String, Object> result = testing.baseline(
                SCOPE, "sol:cancel", "caseSet:cancel", "GREEN");

        assertThat(result).containsEntry("status", "GO")
                .containsEntry("realExternalCalls", 0)
                .containsKeys("goldenSetId", "evidenceRef");
        assertThat((List<?>) result.get("businessBacklog")).isEmpty();
        assertThat(writes).hasValue(0);
        assertThat(states.find(SCOPE, SolutionTestingService.SOLUTION_EVIDENCE, "sol:cancel")).isPresent();
        assertThat(states.find(SCOPE, AgentTddMutationService.CASE_SET, "caseSet:cancel")
                .orElseThrow().data().at("/rows/0/qualityState").asText()).isEqualTo("READY");
    }

    @Test
    void reportsBusinessBacklogAndRefusesUnapprovedOracleRows() {
        storeCases("ACTIVE", Map.of("result", Map.of("decision", "UPHELD")));
        Map<String, Object> failed = testing.baseline(
                SCOPE, "sol:cancel", "caseSet:cancel", "RED");
        assertThat(failed).containsEntry("status", "NO_GO");
        assertThat((List<?>) failed.get("businessBacklog")).hasSize(1);

        storeCases("DRAFT", Map.of("result", Map.of("decision", "WAIVED")));
        assertThatThrownBy(() -> testing.baseline(
                SCOPE, "sol:cancel", "caseSet:cancel", "GREEN"))
                .isInstanceOf(AgentTddToolException.class)
                .extracting(failure -> ((AgentTddToolException) failure).code())
                .isEqualTo("GOLDEN_REQUIRES_APPROVAL");
    }

    private void storeCases(String lifecycle, Map<String, Object> expect) {
        ObjectNode data = mapper.createObjectNode();
        data.put("caseSetRef", "caseSet:cancel");
        data.put("toolRef", "sol:cancel");
        data.set("rows", mapper.valueToTree(List.of(Map.of(
                "caseId", "g1", "category", "GOLDEN", "lifecycle", lifecycle,
                "qualityState", "DESIGNED_NOT_RUN", "oracleOwner", "cx-ops",
                "given", Map.of("party", "none", "orderId", "O-1"),
                "stubs", Map.of(), "expect", expect))));
        states.save(SCOPE, AgentTddMutationService.CASE_SET, "caseSet:cancel", data);
    }
}
