package com.leanowtech.bloge.gateway.visual.simulation;

import com.leanowtech.bloge.gateway.visual.catalog.DefaultVisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Endpoint-level tests for {@link VisualGraphSimulationController} using direct instantiation,
 * matching the existing visual controller test conventions.
 */
class VisualGraphSimulationControllerTest {

    @Test
    void simulateEndpointRunsDesignOnlyDraftThroughMocks() {
        VisualGraphSimulationController controller = controllerFor(
                VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer"));

        VisualGraphSimulationResponse response = controller.simulate(
                new VisualGraphSimulationRequest(eligibilityDraft(), Map.of(), ""));

        assertThat(response.success()).isTrue();
        assertThat(response.mockedNodeIds()).containsExactly("eligibility");
        assertThat(response.output()).isEqualTo(Map.of("eligible", false, "ruleId", "string"));
    }

    @Test
    void simulateEndpointRunsDslPrimitiveForReal() {
        VisualGraphSimulationController controller = controllerFor(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));

        VisualGraphSimulationResponse response = controller.simulate(
                new VisualGraphSimulationRequest(eligibilityDraft(), Map.of("score", 720, "amount", 250_000), ""));

        assertThat(response.success()).isTrue();
        assertThat(response.realNodeIds()).containsExactly("eligibility");
        assertThat(response.output()).isEqualTo(Map.of("eligible", true, "ruleId", "ELIGIBILITY_V1"));
    }

    private static VisualGraphSimulationController controllerFor(
            com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary library) {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(library);
        VisualGraphSimulationService service = new VisualGraphSimulationService(
                new GraphDraftValidator(catalog), catalog, new JsonSchemaSampleGenerator());
        return new VisualGraphSimulationController(service);
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
