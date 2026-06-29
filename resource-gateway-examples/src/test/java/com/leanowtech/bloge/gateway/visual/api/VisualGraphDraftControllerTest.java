package com.leanowtech.bloge.gateway.visual.api;

import com.leanowtech.bloge.gateway.visual.catalog.DefaultVisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.codegen.GraphDraftDslGenerator;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.InMemoryGraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for visual graph draft APIs.
 */
class VisualGraphDraftControllerTest {

    @Test
    void compileBlocksInvalidDraftBeforeDslGeneration() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();
        GraphDraft draft = eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "string"),
                        "amount", Map.of("type", "number")
                )
        ));

        DslGenerationResult result = controller.compile(draft);

        assertThat(result.generated()).isFalse();
        assertThat(result.dsl()).isBlank();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.typeMismatch");
                    assertThat(diagnostic.message()).contains("ctx.score").contains("string").contains("integer");
                });
    }

    @Test
    void compileGeneratesDslAfterVisualValidationPasses() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();
        GraphDraft draft = eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        ));

        DslGenerationResult result = controller.compile(draft);

        assertThat(result.generated()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.dsl()).contains("transform eligibility");
    }

    @Test
    void createStoresCurrentOperatorFingerprintSnapshot() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        VisualGraphDraftController controller = controllerWithCatalog(catalog, new InMemoryGraphDraftRepository());
        GraphDraft draft = eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        ));

        GraphDraft stored = controller.create(draft);

        assertThat(stored.operatorFingerprints())
                .containsEntry("eligibility", catalog.find("risk:eligibility").orElseThrow().fingerprint());
    }

    private static VisualGraphDraftController controllerWithEligibilityLibrary() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        return controllerWithCatalog(catalog, null);
    }

    private static VisualGraphDraftController controllerWithCatalog(DefaultVisualOperatorCatalog catalog,
                                                                    InMemoryGraphDraftRepository repository) {
        return new VisualGraphDraftController(
                repository,
                new GraphDraftValidator(catalog),
                new GraphDraftDslGenerator(catalog),
                null,
                catalog
        );
    }

    private static GraphDraft eligibilityDraft(SchemaEnvelope inputSchema) {
        return new GraphDraft(
                "",
                "",
                0,
                "compileGate",
                "",
                "",
                "",
                "",
                inputSchema,
                List.of(new GraphDraft.DraftNode(
                        "eligibility",
                        "risk:eligibility",
                        "",
                        Map.of(
                                "score", GraphDraft.Binding.contextPath("score"),
                                "amount", GraphDraft.Binding.contextPath("amount")
                        ),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("eligibility", "")
        );
    }

    private static SchemaEnvelope graphInputSchema(Map<String, Object> properties) {
        return SchemaEnvelope.object(properties, properties.keySet().stream().toList());
    }
}
