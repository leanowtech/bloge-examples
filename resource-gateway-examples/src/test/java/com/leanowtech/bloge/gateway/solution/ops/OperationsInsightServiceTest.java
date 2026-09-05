package com.leanowtech.bloge.gateway.solution.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.agenttdd.InMemoryAgentTddStateRepository;
import com.leanowtech.bloge.gateway.agenttdd.SolutionTestingService;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies that operating insight is aggregated from zero-payload runtime signals. */
class OperationsInsightServiceTest {
    private static final String SCOPE = "tenant-a|org-a|project-a|test|sg";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
    private final OperationsInsightService service = new OperationsInsightService(states, mapper);

    @Test
    void recordsNoPayloadAndAggregatesRuntimeRatherThanTestCounts() {
        service.record(SCOPE, "sol:cancel", "event-1", Map.of(
                "rulePath", List.of("R1"), "instructionRef", "ins:refund",
                "result", Map.of("decision", "WAIVED", "amount", 18.5),
                "reasoning", "contains business explanation"));
        service.record(SCOPE, "sol:cancel", "event-2", Map.of(
                "rulePath", List.of("R4"), "instructionRef", "ins:escalate-human-ticket",
                "result", Map.of("ticketId", "T-998")));
        service.record(SCOPE, "sol:cancel", "event-3", Map.of(
                "rulePath", List.of("R4"), "instructionRef", "ins:escalate-human-ticket",
                "result", Map.of("ticketId", "T-999")));
        storeRedGolden();

        Map<String, Object> insight = service.performance("sol:cancel", reader());

        assertThat(insight).containsEntry("totalInvocations", 3)
                .containsEntry("escalationRate", 2.0d / 3.0d);
        assertThat(insight.get("hitDistribution").toString()).contains("R1", "R4", "count=2");
        assertThat(insight.get("dispositionDistribution").toString())
                .contains("OBJECT", "count=3");
        assertThat(insight.get("redGolden").toString()).contains("G4");
        assertThat(insight.get("policyGaps").toString())
                .contains("R4", "高频转人工", "G4", "应然仍未通过");

        String persisted = states.list(SCOPE, OperationsInsightService.OPERATIONS_SIGNAL).toString();
        assertThat(persisted).contains("resultKind", "rulePath", "instructionRef")
                .doesNotContain("WAIVED", "18.5", "T-998", "T-999", "business explanation",
                        "result=", "reasoning", "suppliedFacts");
    }

    @Test
    void recordingTheSameEventIsIdempotent() {
        Map<String, Object> result = Map.of(
                "rulePath", List.of("R1"), "instructionRef", "ins:uphold", "result", "UPHELD");
        service.record(SCOPE, "sol:cancel", "event-1", result);
        service.record(SCOPE, "sol:cancel", "event-1", result);

        assertThat(states.list(SCOPE, OperationsInsightService.OPERATIONS_SIGNAL)).hasSize(1);
        assertThat(service.performance("sol:cancel", reader())).containsEntry("totalInvocations", 1);
    }

    private void storeRedGolden() {
        ObjectNode evidence = mapper.createObjectNode().put("solutionRef", "sol:cancel");
        evidence.set("cases", mapper.valueToTree(List.of(Map.of(
                "caseId", "G4", "verdict", "GREEN_FAIL", "rulePath", List.of("R4"),
                "instructionRef", "ins:escalate-human-ticket"))));
        states.save(SCOPE, SolutionTestingService.SOLUTION_EVIDENCE, "sol:cancel", evidence);
    }

    private static IntegrationRequestContext reader() {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test", "sg",
                "WORKLOAD", "ops-1", "", "AGENT_TDD_READ", "corr-1");
    }
}
