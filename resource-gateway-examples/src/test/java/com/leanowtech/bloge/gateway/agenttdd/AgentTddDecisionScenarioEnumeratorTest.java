package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies deterministic threshold neighborhoods and fail-closed combinatorial caps. */
class AgentTddDecisionScenarioEnumeratorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentTddDecisionScenarioEnumerator enumerator =
            new AgentTddDecisionScenarioEnumerator(mapper);

    @Test
    void enumeratesIntegerThresholdNeighborhoodDeterministically() {
        var rows = enumerator.enumerate(draft(Map.of(
                        "inputs", Map.of("seconds", "ctx.seconds"),
                        "rules", List.of(Map.of("id", "R1",
                                "conditions", Map.of("seconds", "seconds <= 120"),
                                "output", Map.of("decision", "waive"))))),
                mapper.valueToTree(Map.of(
                        "decisionTableRef", "policy", "mode", "combinatorial", "maxCases", 3)));

        assertThat(rows).extracting(row -> row.path("given").path("seconds").asInt())
                .containsExactly(119, 120, 121);
        assertThat(rows).extracting(row -> row.path("caseId").asText())
                .containsExactly("policy-boundary-1", "policy-boundary-2", "policy-boundary-3");
    }

    @Test
    void rejectsCartesianProductsBeyondTheDeclaredCap() {
        assertThatThrownBy(() -> enumerator.enumerate(draft(Map.of(
                            "rules", List.of(Map.of("id", "R1", "conditions", Map.of(
                                    "seconds", "seconds <= 120", "fee", "fee >= 10"))))),
                    mapper.valueToTree(Map.of(
                            "decisionTableRef", "policy", "mode", "combinatorial", "maxCases", 8))))
                .isInstanceOf(AgentTddToolException.class)
                .hasMessageContaining("exceeds maxCases 8");
    }

    private static GraphDraft draft(Map<String, Object> config) {
        return new GraphDraft(GraphDraft.SCHEMA_VERSION, "tool", 1, "tool",
                "demo-tenant", "project-a", "local", GraphDraft.STATUS_DRAFT,
                SchemaEnvelope.opaque(), SchemaEnvelope.opaque(),
                List.of(new GraphDraft.DraftNode(
                        "policy", "bloge:decisionTable", "Policy", Map.of(), config, null)),
                List.of(), Map.of(), Map.of(), new GraphDraft.OutputSelection("policy", ""),
                Map.of(), Map.of(), GraphDraft.RevisionMetadata.empty());
    }
}
