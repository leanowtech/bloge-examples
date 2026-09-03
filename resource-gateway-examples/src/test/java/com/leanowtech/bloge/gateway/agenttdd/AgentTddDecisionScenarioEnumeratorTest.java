package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies the bounded Appendix-D grammar, deterministic ordering and fail-closed caps. */
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

    @Test
    void perRuleCreatesProposedGoldenRepresentativeAndLinearBoundaryNeighbors() {
        var rows = enumerator.enumerate(draft(Map.of(
                        "rules", List.of(Map.of("id", "R1",
                                "conditions", Map.of("seconds", "0 <= seconds < 120"),
                                "output", Map.of("decision", "WAIVE"))))),
                mapper.valueToTree(Map.of("decisionTableRef", "policy", "mode", "per-rule",
                        "maxCases", 5, "oracleOwner", "pricing-owner")));

        assertThat(rows).hasSize(5);
        assertThat(rows.getFirst().path("category").asText()).isEqualTo("GOLDEN");
        assertThat(rows.getFirst().path("given").path("seconds").decimalValue())
                .isEqualByComparingTo("60");
        assertThat(rows.getFirst().path("expect").path("decision").asText()).isEqualTo("WAIVE");
        assertThat(rows.getFirst().path("oracleOwner").asText()).isEqualTo("pricing-owner");
        assertThat(rows.subList(1, 5)).allSatisfy(row ->
                assertThat(row.path("category").asText()).isEqualTo("BOUNDARY"));
        assertThat(rows).extracting(row -> row.path("given").path("seconds").asInt())
                .containsExactly(60, -1, 0, 120, 121);
    }

    @Test
    void parsesNotEqualAndFiniteMembershipWithoutLosingStringMembers() {
        var rows = enumerator.enumerate(draft(Map.of("rules", List.of(
                        Map.of("id", "R1", "conditions", Map.of(
                                "score", "score != 10", "tier", "tier in {\"FREE\",\"PRO\"}"),
                                "output", Map.of("accepted", true))))),
                mapper.valueToTree(Map.of("decisionTableRef", "policy", "mode", "combinatorial",
                        "maxCases", 6)));

        assertThat(rows).hasSize(6);
        assertThat(rows).extracting(row -> row.path("given").path("tier").asText())
                .containsOnly("FREE", "PRO");
        assertThat(rows).extracting(row -> row.path("given").path("score").asInt())
                .containsOnly(9, 10, 11);
    }

    @Test
    void opaquePredicateWithoutAuthorSamplesProducesBlockedCase() {
        var rows = enumerator.enumerate(draft(Map.of("rules", List.of(
                        Map.of("id", "R1", "conditions", Map.of("country", "isSupported(country)"),
                                "output", Map.of("accepted", true))))),
                mapper.valueToTree(Map.of("decisionTableRef", "policy", "mode", "per-rule",
                        "maxCases", 3)));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.path("category").asText()).isEqualTo("GOLDEN");
            assertThat(row.path("qualityState").asText()).isEqualTo("BLOCKED");
            assertThat(row.path("enumeration").path("reason").asText())
                    .isEqualTo("AUTHOR_SAMPLES_REQUIRED");
        });
    }

    @Test
    void authorSamplesMakeOpaqueRuleDeterministicallyEnumerable() {
        var request = mapper.valueToTree(Map.of("decisionTableRef", "policy", "mode", "per-rule",
                "maxCases", 3, "oracleOwner", "risk-owner",
                "authorSamples", Map.of("country", List.of("SG", "MY"))));
        GraphDraft draft = draft(Map.of("rules", List.of(
                Map.of("id", "R1", "conditions", Map.of("country", "isSupported(country)"),
                        "output", Map.of("accepted", true)))));

        var first = enumerator.enumerate(draft, request);
        var second = enumerator.enumerate(draft, request);

        assertThat(second).isEqualTo(first);
        assertThat(first).extracting(row -> row.path("given").path("country").asText())
                .containsExactly("SG", "MY");
        assertThat(first).extracting(row -> row.path("category").asText())
                .containsExactly("GOLDEN", "BOUNDARY");
    }

    @Test
    void parsesStringEqualityAsOneFiniteRepresentative() {
        var rows = enumerator.enumerate(draft(Map.of("rules", List.of(
                        Map.of("id", "R1", "conditions", Map.of("status", "status == \"ACTIVE\""),
                                "output", Map.of("accepted", true))))),
                mapper.valueToTree(Map.of("decisionTableRef", "policy", "mode", "per-rule",
                        "maxCases", 1, "oracleOwner", "policy-owner")));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.path("category").asText()).isEqualTo("GOLDEN");
            assertThat(row.path("given").path("status").asText()).isEqualTo("ACTIVE");
        });
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
