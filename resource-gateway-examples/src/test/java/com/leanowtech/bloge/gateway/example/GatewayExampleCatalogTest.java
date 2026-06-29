package com.leanowtech.bloge.gateway.example;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for the example catalog that feeds the browser showcase.
 */
class GatewayExampleCatalogTest {

    private final GatewayExampleCatalog catalog = new GatewayExampleCatalog();

    @Test
    void listsAllGatewayGraphsInShowcaseOrder() {
        assertThat(catalog.scenarios())
                .extracting(GatewayExampleScenario::graphName)
                .containsExactly(
                        "userDashboard",
                        "loanDecisionPolicy",
                        "productDetail",
                        "enrichOrderList",
                        "creditScore",
                        "resourceDispatch",
                        "aiEnrichedSearch"
                );
    }

    @Test
    void everyScenarioHasExecutableRecipeAndDiagram() {
        for (GatewayExampleScenario scenario : catalog.scenarios()) {
            assertThat(scenario.graphFile()).endsWith(".bloge");
            assertThat(scenario.sampleInput()).isNotEmpty();
            assertThat(scenario.concepts()).isNotEmpty();
            assertThat(scenario.run().pathTemplate()).startsWith("/api/gateway");
            assertThat(scenario.diagramPath()).isEqualTo(
                    "/api/gateway/examples/scenarios/" + scenario.graphName() + "/diagram");

            ExampleVisualLayout layout = catalog.diagram(scenario.graphName()).orElseThrow();
            assertThat(layout.schemaVersion()).isEqualTo(ExampleVisualLayout.SCHEMA_VERSION);
            assertThat(layout.rootId()).isEqualTo(scenario.graphName());
            assertThat(layout.nodes()).isNotEmpty();
        }
    }

    @Test
    void loanPolicyScenarioExposesDecisionTableMetadata() {
        GatewayExampleScenario scenario = catalog.scenario("loanDecisionPolicy").orElseThrow();

        assertThat(scenario.decisionTable()).isNotNull();
        assertThat(scenario.decisionTable().hitPolicy()).isEqualTo("unique");
        assertThat(scenario.decisionTable().rows())
                .extracting(GatewayDecisionTable.Row::id)
                .containsExactly("R1", "R2", "R3", "R4");
    }

    @Test
    void diagramEdgesOnlyReferenceDeclaredNodes() {
        for (GatewayExampleScenario scenario : catalog.scenarios()) {
            ExampleVisualLayout layout = catalog.diagram(scenario.graphName()).orElseThrow();
            Set<String> nodeIds = layout.nodes().stream()
                    .map(ExampleVisualLayout.Node::id)
                    .collect(Collectors.toUnmodifiableSet());

            assertThat(layout.edges())
                    .allSatisfy(edge -> {
                        assertThat(nodeIds).contains(edge.source());
                        assertThat(nodeIds).contains(edge.target());
                    });
        }
    }
}
