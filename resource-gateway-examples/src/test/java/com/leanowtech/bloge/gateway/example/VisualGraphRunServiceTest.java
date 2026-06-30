package com.leanowtech.bloge.gateway.example;

import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.codegen.GraphDraftDslGenerator;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunResponse;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunService;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import com.leanowtech.bloge.test.MockOperator;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
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
        GraphDraft draft = withFingerprints(new GraphDraft(
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
        ), catalog);

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
        GraphDraft draft = withFingerprints(new GraphDraft(
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
        ), catalog);

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
        GraphDraft draft = withFingerprints(new GraphDraft(
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
        ), catalog);

        VisualGraphRunResponse defaultResponse = service.run(draft, Map.of("score", 720, "amount", 250_000), "");
        VisualGraphRunResponse overrideResponse = service.run(draft, Map.of("score", 720, "amount", 250_000), "summary");

        assertThat(defaultResponse.output()).isEqualTo(true);
        assertThat(overrideResponse.outputNode()).isEqualTo("summary");
        assertThat(overrideResponse.output()).isEqualTo(Map.of("score", 720, "amount", 250_000));
    }

    @Test
    void runsPublishedArtifactFromFrozenDslWithoutCurrentCatalog() {
        VisualGraphRunService service = new VisualGraphRunService(
                null,
                null,
                new DynamicGatewayComposerService(MockOperator.returning(null))
        );
        GraphDraft draft = new GraphDraft(
                "",
                "draft-1",
                2,
                "publishedPolicy",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "response",
                        "risk:operatorRemovedFromCurrentCatalog",
                        "",
                        Map.of(),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("response", "score")
        );
        String frozenDsl = """
                graph publishedPolicy {
                  transform response {
                    score = ctx.score
                  }
                }
                """;
        VisualGraphPublication publication = new VisualGraphPublication(
                "",
                "pub-1",
                draft.draftId(),
                draft.revision(),
                draft.graphName(),
                draft.tenantId(),
                draft.namespace(),
                draft.environment(),
                null,
                draft,
                List.of(),
                Map.of(),
                Map.of(),
                frozenDsl,
                new VisualValidationResult(true, List.of()),
                new DslGenerationResult(true, frozenDsl, List.of())
        );

        VisualGraphRunResponse response = service.run(publication, Map.of("score", 720), "");

        assertThat(response.validated()).isTrue();
        assertThat(response.compiled()).isTrue();
        assertThat(response.success()).isTrue();
        assertThat(response.outputNode()).isEqualTo("response");
        assertThat(response.output()).isEqualTo(720);
        assertThat(response.generatedDsl()).isEqualTo(frozenDsl);
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

    private static GraphDraft withFingerprints(GraphDraft draft, VisualOperatorCatalog catalog) {
        Map<String, String> fingerprints = new LinkedHashMap<>();
        for (GraphDraft.DraftNode node : draft.nodes()) {
            catalog.find(node.operatorRef())
                    .ifPresent(operator -> fingerprints.put(node.id(), operator.fingerprint()));
        }
        return draft.withOperatorFingerprints(fingerprints);
    }
}
