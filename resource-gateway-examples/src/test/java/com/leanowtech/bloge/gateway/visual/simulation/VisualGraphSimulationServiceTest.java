package com.leanowtech.bloge.gateway.visual.simulation;

import com.leanowtech.bloge.gateway.visual.catalog.DefaultVisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link VisualGraphSimulationService}: design-only graphs run via mocks,
 * DSL-primitive nodes run for real (hybrid), and resource caps are enforced.
 */
class VisualGraphSimulationServiceTest {

    @Test
    void simulatesDesignOnlyGraphByMockingTheOperator() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer"));
        VisualGraphSimulationService service = simulationService(catalog);

        GraphDraft draft = eligibilityDraft();

        VisualGraphSimulationResponse response = service.simulate(draft, Map.of(), "");

        // The design-only operator cannot run normally, but simulate makes the graph executable.
        assertThat(response.validated()).isTrue();
        assertThat(response.compiled()).isTrue();
        assertThat(response.success()).isTrue();
        assertThat(response.errors()).isEmpty();
        assertThat(response.mockedNodeIds()).containsExactly("eligibility");
        assertThat(response.realNodeIds()).isEmpty();
        assertThat(response.generatedDsl()).contains("__sim_eligibility");
        // Output is synthesized from the declared output schema {eligible: boolean, ruleId: string}.
        assertThat(response.output()).isEqualTo(Map.of("eligible", false, "ruleId", "string"));
        assertThat(response.terminalOutputConforms()).isTrue();
    }

    @Test
    void runsDslPrimitiveNodesForRealInHybridMode() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        VisualGraphSimulationService service = simulationService(catalog);

        GraphDraft draft = eligibilityDraft();

        VisualGraphSimulationResponse response = service.simulate(
                draft, Map.of("score", 720, "amount", 250_000), "");

        // The eligibility operator lowers to a transform (a DSL primitive), so it executes for real.
        assertThat(response.success()).isTrue();
        assertThat(response.realNodeIds()).containsExactly("eligibility");
        assertThat(response.mockedNodeIds()).isEmpty();
        assertThat(response.generatedDsl()).doesNotContain("__sim_");
        assertThat(response.output()).isEqualTo(Map.of("eligible", true, "ruleId", "ELIGIBILITY_V1"));
    }

    @Test
    void blocksSimulationWhenNodeCapExceeded() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer"));
        VisualGraphSimulationService service = simulationService(catalog);

        List<GraphDraft.DraftNode> nodes = new ArrayList<>();
        for (int i = 0; i <= VisualGraphSimulationService.MAX_SIMULATION_NODES; i++) {
            nodes.add(new GraphDraft.DraftNode("n" + i, "risk:eligibility", "", Map.of(), Map.of(), null));
        }
        GraphDraft draft = new GraphDraft(
                "", "", 0, "tooBig", "", "", "", "", null,
                nodes, List.of(), Map.of(),
                new GraphDraft.OutputSelection("n0", ""));

        VisualGraphSimulationResponse response = service.simulate(draft, Map.of(), "");

        assertThat(response.validated()).isFalse();
        assertThat(response.success()).isFalse();
        assertThat(response.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.code())
                        .isEqualTo("visual.simulate.nodeCapExceeded"));
    }

    private static VisualGraphSimulationService simulationService(DefaultVisualOperatorCatalog catalog) {
        return new VisualGraphSimulationService(
                new GraphDraftValidator(catalog),
                catalog,
                new JsonSchemaSampleGenerator());
    }

    private static GraphDraft eligibilityDraft() {
        return new GraphDraft(
                "", "", 0, "eligibilityPolicy", "", "", "", "", null,
                List.of(new GraphDraft.DraftNode(
                        "eligibility",
                        "risk:eligibility",
                        "",
                        Map.of(
                                "score", GraphDraft.Binding.contextPath("score"),
                                "amount", GraphDraft.Binding.contextPath("amount")),
                        Map.of(),
                        null)),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("eligibility", ""));
    }
}
