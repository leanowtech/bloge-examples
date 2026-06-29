package com.leanowtech.bloge.gateway.example;

import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.codegen.GraphDraftDslGenerator;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunResponse;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunService;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import com.leanowtech.bloge.test.MockOperator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runtime smoke tests for visual graph drafts.
 */
class VisualGraphRunServiceTest {

    @Test
    void runsTransformDraftThroughExistingDynamicComposer() {
        VisualOperatorCatalog catalog = transformOnlyCatalog();
        VisualGraphRunService service = new VisualGraphRunService(
                new GraphDraftValidator(catalog),
                new GraphDraftDslGenerator(catalog),
                new DynamicGatewayComposerService(MockOperator.returning(null))
        );
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "visualPolicy",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "response",
                        "bloge:transform",
                        "",
                        Map.of("score", GraphDraft.Binding.contextPath("score")),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("response", "")
        );

        VisualGraphRunResponse response = service.run(draft, Map.of("score", 720), "");

        assertThat(response.validated()).isTrue();
        assertThat(response.compiled()).isTrue();
        assertThat(response.success()).isTrue();
        assertThat(response.generatedDsl()).contains("transform response");
        assertThat(response.output()).isEqualTo(Map.of("score", 720));
    }

    @Test
    void runsUserProvidedTransformOperatorLibraryDefinition() {
        VisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        VisualGraphRunService service = new VisualGraphRunService(
                new GraphDraftValidator(catalog),
                new GraphDraftDslGenerator(catalog),
                new DynamicGatewayComposerService(MockOperator.returning(null))
        );
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "eligibilityPolicy",
                "",
                "",
                "",
                "",
                null,
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

        VisualGraphRunResponse response = service.run(draft, Map.of("score", 720, "amount", 250_000), "");

        assertThat(response.validated()).isTrue();
        assertThat(response.compiled()).isTrue();
        assertThat(response.success()).isTrue();
        assertThat(response.generatedDsl()).contains("eligible = ctx.score >= 700 && ctx.amount <= 300000");
        assertThat(response.output()).isEqualTo(Map.of("eligible", true, "ruleId", "ELIGIBILITY_V1"));
    }

    @Test
    void outputNodeOverrideDoesNotReuseDraftOutputPath() {
        VisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        VisualGraphRunService service = new VisualGraphRunService(
                new GraphDraftValidator(catalog),
                new GraphDraftDslGenerator(catalog),
                new DynamicGatewayComposerService(MockOperator.returning(null))
        );
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "overrideOutputPolicy",
                "",
                "",
                "",
                "",
                null,
                List.of(
                        new GraphDraft.DraftNode(
                                "eligibility",
                                "risk:eligibility",
                                "",
                                Map.of(
                                        "score", GraphDraft.Binding.contextPath("score"),
                                        "amount", GraphDraft.Binding.contextPath("amount")
                                ),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "summary",
                                "bloge:transform",
                                "",
                                Map.of(),
                                Map.of("assignments", Map.of(
                                        "score", "ctx.score",
                                        "amount", "ctx.amount"
                                )),
                                null
                        )
                ),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("eligibility", "eligible")
        );

        VisualGraphRunResponse defaultResponse = service.run(draft, Map.of("score", 720, "amount", 250_000), "");
        VisualGraphRunResponse overrideResponse = service.run(draft, Map.of("score", 720, "amount", 250_000), "summary");

        assertThat(defaultResponse.output()).isEqualTo(true);
        assertThat(overrideResponse.outputNode()).isEqualTo("summary");
        assertThat(overrideResponse.output()).isEqualTo(Map.of("score", 720, "amount", 250_000));
    }

    private static VisualOperatorCatalog transformOnlyCatalog() {
        return new VisualOperatorCatalog() {
            private final com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition transform =
                    new com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition(
                            "bloge.visualOperator.v1",
                            "bloge:transform",
                            "1.0.0",
                            null,
                            null,
                            null,
                            null,
                            null,
                            new com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition.Lowering(
                                    "dsl", "transform", Map.of()),
                            List.of()
                    );

            @Override
            public List<com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition> list(
                    com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery query) {
                return List.of(transform);
            }

            @Override
            public Optional<com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition> find(String operatorRef) {
                return "bloge:transform".equals(operatorRef) ? Optional.of(transform) : Optional.empty();
            }
        };
    }
}
