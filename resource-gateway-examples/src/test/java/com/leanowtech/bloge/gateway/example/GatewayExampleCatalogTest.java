package com.leanowtech.bloge.gateway.example;

import org.junit.jupiter.api.Test;

import java.util.Map;
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
            assertThat(scenario.inputSchema().schema()).containsEntry("type", "object");
            assertThat(scenario.outputSchema().schema()).containsEntry("type", "object");
            assertThat(scenario.diagramPath()).isEqualTo(
                    "/api/gateway/examples/scenarios/" + scenario.graphName() + "/diagram");

            ExampleVisualLayout layout = catalog.diagram(scenario.graphName()).orElseThrow();
            assertThat(layout.schemaVersion()).isEqualTo(ExampleVisualLayout.SCHEMA_VERSION);
            assertThat(layout.rootId()).isEqualTo(scenario.graphName());
            assertThat(layout.nodes()).isNotEmpty();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void firstThreeShowcaseExamplesExposeTheirOwnGraphContracts() {
        assertThat(catalog.scenarios().stream().limit(3).map(GatewayExampleScenario::graphName).toList())
                .containsExactly("userDashboard", "loanDecisionPolicy", "productDetail");

        Map<String, Object> dashboardInput = (Map<String, Object>) catalog.scenario("userDashboard")
                .orElseThrow()
                .inputSchema()
                .schema()
                .get("properties");
        Map<String, Object> dashboardOutput = (Map<String, Object>) catalog.scenario("userDashboard")
                .orElseThrow()
                .outputSchema()
                .schema()
                .get("properties");
        Map<String, Object> loanInput = (Map<String, Object>) catalog.scenario("loanDecisionPolicy")
                .orElseThrow()
                .inputSchema()
                .schema()
                .get("properties");
        Map<String, Object> productOutput = (Map<String, Object>) catalog.scenario("productDetail")
                .orElseThrow()
                .outputSchema()
                .schema()
                .get("properties");

        assertThat(dashboardInput).containsKey("userId");
        assertThat(dashboardOutput).containsKeys("profile", "orders", "wallet");
        assertThat(loanInput).containsKeys("applicantId", "requestedAmount");
        assertThat(productOutput).containsKeys("product", "productType");
    }

    @Test
    void loanPolicyScenarioExposesDecisionTableMetadata() {
        GatewayExampleScenario scenario = catalog.scenario("loanDecisionPolicy").orElseThrow();

        assertThat(scenario.decisionTable()).isNotNull();
        assertThat(scenario.decisionTable().hitPolicy()).isEqualTo("unique");
        assertThat(scenario.decisionTable().rows())
                .extracting(GatewayDecisionTable.Row::id)
                .containsExactly("R1", "R2", "R3", "R4");
        assertThat(scenario.samplePresets())
                .extracting(GatewayExamplePreset::label)
                .containsExactly("Prime auto approve", "Standard approve", "Manual review", "Decline");
        assertThat(scenario.samplePresets())
                .extracting(preset -> preset.expected().get("ruleId"))
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
