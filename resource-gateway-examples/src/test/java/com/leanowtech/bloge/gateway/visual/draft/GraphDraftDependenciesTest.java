package com.leanowtech.bloge.gateway.visual.draft;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for implicit visual graph dependency extraction.
 */
class GraphDraftDependenciesTest {

    @Test
    void extractsBracketPathNodeReferencesFromBindingsAndConfig() {
        GraphDraft.DraftNode node = new GraphDraft.DraftNode(
                "consumer",
                "risk:consumer",
                "",
                Map.of("score", GraphDraft.Binding.expression("listFacts.output.items[0].score + 1")),
                Map.of("threshold", Map.of(
                        "kind", "expression",
                        "expr", "policyConfig.output.thresholds[0]"
                )),
                null
        );

        assertThat(GraphDraftDependencies.nodeDependencies(node))
                .containsExactly("listFacts", "policyConfig");
    }

    @Test
    void ignoresMalformedBracketPathNodeReferences() {
        GraphDraft.DraftNode node = new GraphDraft.DraftNode(
                "consumer",
                "risk:consumer",
                "",
                Map.of("score", GraphDraft.Binding.expression("listFacts.output.items[bad].score")),
                Map.of("threshold", "policyConfig.output.thresholds[+1]"),
                null
        );

        assertThat(GraphDraftDependencies.nodeDependencies(node)).isEmpty();
    }
}
