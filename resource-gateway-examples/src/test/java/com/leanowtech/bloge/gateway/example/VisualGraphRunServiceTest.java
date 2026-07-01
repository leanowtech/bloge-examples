package com.leanowtech.bloge.gateway.example;

import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.DefaultVisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.catalog.JavaOperatorInventoryProjector;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.ResourceVirtualOperatorProjector;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.catalog.VisualGraphPublicationOperatorProjector;
import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.codegen.GraphDraftDslGenerator;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.publication.InMemoryVisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.resource.InMemoryResourceDesignContractRegistry;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphPublicationOperator;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunResponse;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunService;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import com.leanowtech.bloge.test.MockOperator;

import org.springframework.beans.factory.ObjectProvider;
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
        assertThat(response.errors()).isEmpty();
        assertThat(response.success()).isTrue();
        assertThat(response.generatedDsl()).contains("transform response");
        assertThat(response.output()).isEqualTo(Map.of("score", 720));
        assertThat(response.nodeElapsedMs()).containsKey("response");
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
        assertThat(response.errors()).isEmpty();
        assertThat(response.success()).isTrue();
        assertThat(response.generatedDsl()).contains("eligible = ctx.score >= 700 && ctx.amount <= 300000");
        assertThat(response.output()).isEqualTo(Map.of("eligible", true, "ruleId", "ELIGIBILITY_V1"));
    }

    @Test
    void runsJavaOperatorProjectedFromRuntimeRegistry() {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.register("normalizeText", new NormalizeTextOperator());
        VisualOperatorCatalog catalog = new DefaultVisualOperatorCatalog(
                VisualCatalogTestSupport.emptyResourceRegistry(),
                new InMemoryResourceDesignContractRegistry(),
                new ResourceVirtualOperatorProjector(),
                OperatorLibraryRegistry.empty(),
                JavaOperatorInventoryProjector.forRegistry(registry)
        );
        VisualGraphRunService service = new VisualGraphRunService(
                new GraphDraftValidator(catalog),
                new GraphDraftDslGenerator(catalog),
                new DynamicGatewayComposerService(registry)
        );
        GraphDraft draft = withFingerprints(new GraphDraft(
                "",
                "",
                0,
                "javaOperatorPolicy",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "normalize",
                        "normalizeText",
                        "",
                        Map.of("raw", GraphDraft.Binding.contextPath("text")),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("normalize", "value")
        ), catalog);

        VisualGraphRunResponse response = service.run(draft, Map.of("text", "  Hello BLOGE  "), "");

        assertThat(response.validated()).isTrue();
        assertThat(response.compiled()).isTrue();
        assertThat(response.errors()).isEmpty();
        assertThat(response.success()).isTrue();
        assertThat(response.generatedDsl()).contains("node normalize : normalizeText");
        assertThat(response.output()).isEqualTo("hello bloge");
    }

    @Test
    void extractsArrayIndexFromSelectedOutputPath() {
        SchemaEnvelope scoresSchema = SchemaEnvelope.object(Map.of(
                "scores", Map.of(
                        "type", "array",
                        "items", Map.of("type", "integer"))
        ), List.of("scores"));
        VisualOperatorCatalog catalog = transformCatalogWithOutput(scoresSchema);
        VisualGraphRunService service = new VisualGraphRunService(
                new GraphDraftValidator(catalog),
                new GraphDraftDslGenerator(catalog),
                new DynamicGatewayComposerService(MockOperator.returning(null))
        );
        GraphDraft draft = withFingerprints(new GraphDraft(
                "",
                "",
                0,
                "arrayOutputPolicy",
                "",
                "",
                "",
                "",
                scoresSchema,
                List.of(new GraphDraft.DraftNode(
                        "response",
                        "bloge:transform",
                        "",
                        Map.of("scores", GraphDraft.Binding.contextPath("scores")),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("response", "scores.1")
        ), catalog);

        VisualGraphRunResponse response = service.run(draft, Map.of("scores", List.of(610, 720)), "");

        assertThat(response.validated()).isTrue();
        assertThat(response.compiled()).isTrue();
        assertThat(response.errors()).isEmpty();
        assertThat(response.success()).isTrue();
        assertThat(response.output()).isEqualTo(720);
    }

    @Test
    void runsContextArrayIndexBindingThroughGeneratedDsl() {
        SchemaEnvelope inputSchema = SchemaEnvelope.object(Map.of(
                "scores", Map.of(
                        "type", "array",
                        "items", Map.of("type", "integer"))
        ), List.of("scores"));
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
                "arrayIndexBindingPolicy",
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
                                "score", GraphDraft.Binding.contextPath("scores.0"),
                                "amount", GraphDraft.Binding.constant(250_000)
                        ),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("eligibility", "")
        ), catalog);

        VisualGraphRunResponse response = service.run(draft, Map.of("scores", List.of(720, 610)), "");

        assertThat(response.validated()).isTrue();
        assertThat(response.compiled()).isTrue();
        assertThat(response.errors()).isEmpty();
        assertThat(response.success()).isTrue();
        assertThat(response.generatedDsl()).contains("eligible = ctx.scores[0] >= 700");
        assertThat(response.output()).isEqualTo(Map.of("eligible", true, "ruleId", "ELIGIBILITY_V1"));
    }

    @Test
    void rejectsDraftRunWhenContextViolatesInputSchema() {
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
                eligibilityInputSchema(),
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

        VisualGraphRunResponse response = service.run(draft, Map.of("score", "720", "amount", 250_000), "");

        assertThat(response.validated()).isTrue();
        assertThat(response.compiled()).isFalse();
        assertThat(response.success()).isFalse();
        assertThat(response.errors()).contains("Runtime context validation failed.");
        assertThat(response.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.context.typeMismatch");
                    assertThat(diagnostic.target()).isEqualTo("/context/score");
                });
    }

    @Test
    void ignoresUndeclaredSystemContextFieldsDuringRuntimeInputSchemaValidation() {
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
                "draft-tenant",
                "draft-namespace",
                "",
                "",
                eligibilityInputSchema(),
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

        VisualGraphRunResponse response = service.run(draft, Map.of(
                "score", 720,
                "amount", 250_000,
                "tenantId", "caller-tenant",
                "namespace", "caller-namespace",
                "_blogeTraceId", "trace-1"
        ), "");

        assertThat(response.validated()).isTrue();
        assertThat(response.compiled()).isTrue();
        assertThat(response.errors()).isEmpty();
        assertThat(response.success()).isTrue();
        assertThat(response.output()).isEqualTo(Map.of("eligible", true, "ruleId", "ELIGIBILITY_V1"));
        assertThat(response.diagnostics())
                .noneSatisfy(diagnostic -> assertThat(diagnostic.target())
                        .isIn("/context/tenantId", "/context/namespace", "/context/_blogeTraceId"));
    }

    @Test
    void validatesSystemNamedContextFieldsWhenDeclaredByBusinessInputSchema() {
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
                "tenantAwareEligibilityPolicy",
                "",
                "",
                "",
                "",
                tenantAwareEligibilityInputSchema(),
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

        VisualGraphRunResponse response = service.run(draft, Map.of(
                "score", 720,
                "amount", 250_000,
                "tenantId", "caller-tenant"
        ), "");

        assertThat(response.validated()).isTrue();
        assertThat(response.compiled()).isFalse();
        assertThat(response.success()).isFalse();
        assertThat(response.errors()).contains("Runtime context validation failed.");
        assertThat(response.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.context.typeMismatch");
                    assertThat(diagnostic.target()).isEqualTo("/context/tenantId");
                });
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
    void rejectsUnknownDraftOutputNodeOverrideBeforeDynamicRun() {
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

        VisualGraphRunResponse response = service.run(draft, Map.of("score", 720, "amount", 250_000),
                "missingOutput");

        assertThat(response.validated()).isTrue();
        assertThat(response.compiled()).isFalse();
        assertThat(response.success()).isFalse();
        assertThat(response.errors()).contains("Output node override validation failed.");
        assertThat(response.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.run.outputNode.unknown");
                    assertThat(diagnostic.target()).isEqualTo("/outputNode");
                    assertThat(diagnostic.message()).contains("missingOutput");
                });
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

    @Test
    void rejectsUnknownPublishedOutputNodeOverrideBeforeDynamicRun() {
        VisualGraphRunService service = new VisualGraphRunService(
                null,
                null,
                new DynamicGatewayComposerService(MockOperator.returning(null))
        );
        VisualGraphPublication publication = publishedScoreGraph();

        VisualGraphRunResponse response = service.run(publication, Map.of("score", 720), "missingOutput");

        assertThat(response.validated()).isTrue();
        assertThat(response.compiled()).isFalse();
        assertThat(response.success()).isFalse();
        assertThat(response.generatedDsl()).isEqualTo(publication.dsl());
        assertThat(response.errors()).contains("Output node override validation failed.");
        assertThat(response.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.run.outputNode.unknown");
                    assertThat(diagnostic.target()).isEqualTo("/outputNode");
                    assertThat(diagnostic.message()).contains("missingOutput");
                });
    }

    @Test
    void runsPublishedVisualGraphAsReusableSubgraphOperator() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publishedScoreGraph());
        VisualGraphRunService frozenPublicationRunner = new VisualGraphRunService(
                null,
                null,
                new DynamicGatewayComposerService(MockOperator.returning(null))
        );
        VisualGraphPublicationOperator publicationOperator = new VisualGraphPublicationOperator(
                publications,
                providerFor(frozenPublicationRunner)
        );
        DefaultOperatorRegistry runtimeRegistry = new DefaultOperatorRegistry();
        runtimeRegistry.registerRaw(VisualGraphPublicationOperator.NAME, publicationOperator);
        VisualOperatorCatalog catalog = new DefaultVisualOperatorCatalog(
                VisualCatalogTestSupport.emptyResourceRegistry(),
                new InMemoryResourceDesignContractRegistry(),
                new ResourceVirtualOperatorProjector(),
                OperatorLibraryRegistry.empty(),
                JavaOperatorInventoryProjector.forRegistry(new DefaultOperatorRegistry()),
                publications,
                new VisualGraphPublicationOperatorProjector()
        );
        VisualGraphRunService service = new VisualGraphRunService(
                new GraphDraftValidator(catalog),
                new GraphDraftDslGenerator(catalog),
                new DynamicGatewayComposerService(runtimeRegistry)
        );
        OperatorDefinition operator = catalog.find(VisualGraphPublicationOperatorProjector.operatorRef(
                publication.publicationId())).orElseThrow();
        GraphDraft draft = new GraphDraft(
                "",
                "outer-draft",
                1,
                "outerComposition",
                "",
                "",
                "",
                "",
                SchemaEnvelope.object(Map.of("score", Map.of("type", "integer")), List.of("score")),
                List.of(new GraphDraft.DraftNode(
                        "publishedScore",
                        operator.operatorRef(),
                        "",
                        Map.of("score", GraphDraft.Binding.contextPath("score")),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("publishedScore", ""),
                Map.of("publishedScore", operator.fingerprint())
        );

        VisualGraphRunResponse response = service.run(draft, Map.of("score", 720), "");

        assertThat(response.validated()).isTrue();
        assertThat(response.compiled()).isTrue();
        assertThat(response.errors()).isEmpty();
        assertThat(response.success()).isTrue();
        assertThat(response.generatedDsl()).contains("node publishedScore : visualPublication");
        assertThat(response.generatedDsl()).contains("config = { publicationId: \"pub-score\" }");
        assertThat(response.output()).isEqualTo(720);
    }

    @Test
    void preservesPublicationIdBusinessInputForReusableSubgraphOperator() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publishedPublicationIdEchoGraph());
        VisualGraphRunService frozenPublicationRunner = new VisualGraphRunService(
                null,
                null,
                new DynamicGatewayComposerService(MockOperator.returning(null))
        );
        VisualGraphPublicationOperator publicationOperator = new VisualGraphPublicationOperator(
                publications,
                providerFor(frozenPublicationRunner)
        );
        DefaultOperatorRegistry runtimeRegistry = new DefaultOperatorRegistry();
        runtimeRegistry.registerRaw(VisualGraphPublicationOperator.NAME, publicationOperator);
        VisualOperatorCatalog catalog = new DefaultVisualOperatorCatalog(
                VisualCatalogTestSupport.emptyResourceRegistry(),
                new InMemoryResourceDesignContractRegistry(),
                new ResourceVirtualOperatorProjector(),
                OperatorLibraryRegistry.empty(),
                JavaOperatorInventoryProjector.forRegistry(new DefaultOperatorRegistry()),
                publications,
                new VisualGraphPublicationOperatorProjector()
        );
        VisualGraphRunService service = new VisualGraphRunService(
                new GraphDraftValidator(catalog),
                new GraphDraftDslGenerator(catalog),
                new DynamicGatewayComposerService(runtimeRegistry)
        );
        OperatorDefinition operator = catalog.find(VisualGraphPublicationOperatorProjector.operatorRef(
                publication.publicationId())).orElseThrow();
        GraphDraft draft = new GraphDraft(
                "",
                "outer-publication-id-draft",
                1,
                "outerPublicationIdComposition",
                "",
                "",
                "",
                "",
                SchemaEnvelope.object(Map.of("publicationId", Map.of("type", "string")), List.of("publicationId")),
                List.of(new GraphDraft.DraftNode(
                        "publishedEcho",
                        operator.operatorRef(),
                        "",
                        Map.of("publicationId", GraphDraft.Binding.contextPath("publicationId")),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("publishedEcho", ""),
                Map.of("publishedEcho", operator.fingerprint())
        );

        VisualGraphRunResponse response = service.run(draft, Map.of("publicationId", "business-pub-id"), "");

        assertThat(response.validated()).isTrue();
        assertThat(response.compiled()).isTrue();
        assertThat(response.errors()).isEmpty();
        assertThat(response.success()).isTrue();
        assertThat(response.generatedDsl()).contains("config = { publicationId: \"pub-publication-id-echo\" }");
        assertThat(response.output()).isEqualTo("business-pub-id");
    }

    @Test
    void rejectsPublishedRunWhenContextViolatesFrozenInputSchema() {
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
                eligibilityInputSchema(),
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
                new GraphDraft.OutputSelection("response", "eligible")
        );
        String frozenDsl = """
                graph publishedPolicy {
                  transform response {
                    eligible = ctx.score >= 700 && ctx.amount <= 300000
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
        assertThat(response.compiled()).isFalse();
        assertThat(response.success()).isFalse();
        assertThat(response.generatedDsl()).isEqualTo(frozenDsl);
        assertThat(response.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.code())
                        .isEqualTo("visual.context.requiredMissing"));
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

    private static VisualOperatorCatalog transformCatalogWithOutput(SchemaEnvelope outputSchema) {
        return new VisualOperatorCatalog() {
            private final OperatorDefinition transform = new OperatorDefinition(
                    "bloge.visualOperator.v1",
                    "bloge:transform",
                    "1.0.0",
                    new OperatorDefinition.Display("Transform", "Schema-aware transform.", List.of("logic")),
                    OperatorDefinition.Source.builtIn("bloge-dsl"),
                    new OperatorDefinition.Ports(
                            List.of(new OperatorDefinition.Port("inputs", SchemaEnvelope.opaque(), false,
                                    "Inputs.")),
                            List.of(new OperatorDefinition.Port("output", outputSchema, true, "Output."))
                    ),
                    SchemaEnvelope.opaque(),
                    OperatorDefinition.Capabilities.pure(),
                    new OperatorDefinition.Lowering("dsl", "transform", Map.of()),
                    List.of()
            );

            @Override
            public List<OperatorDefinition> list(
                    com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery query) {
                return List.of(transform);
            }

            @Override
            public Optional<OperatorDefinition> find(String operatorRef) {
                return "bloge:transform".equals(operatorRef) ? Optional.of(transform) : Optional.empty();
            }
        };
    }

    private static SchemaEnvelope eligibilityInputSchema() {
        return SchemaEnvelope.object(Map.of(
                "score", Map.of("type", "integer"),
                "amount", Map.of("type", "number")
        ), List.of("score", "amount"));
    }

    private static SchemaEnvelope tenantAwareEligibilityInputSchema() {
        return SchemaEnvelope.object(Map.of(
                "score", Map.of("type", "integer"),
                "amount", Map.of("type", "number"),
                "tenantId", Map.of("type", "integer")
        ), List.of("score", "amount", "tenantId"));
    }

    private static GraphDraft withFingerprints(GraphDraft draft, VisualOperatorCatalog catalog) {
        Map<String, String> fingerprints = new LinkedHashMap<>();
        for (GraphDraft.DraftNode node : draft.nodes()) {
            catalog.find(node.operatorRef())
                    .ifPresent(operator -> fingerprints.put(node.id(), operator.fingerprint()));
        }
        return draft.withOperatorFingerprints(fingerprints);
    }

    private static VisualGraphPublication publishedScoreGraph() {
        OperatorDefinition transformSnapshot = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "bloge:transform",
                "1.0.0",
                new OperatorDefinition.Display("Transform", "Published transform snapshot.", List.of("logic")),
                OperatorDefinition.Source.builtIn("bloge-dsl"),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs", SchemaEnvelope.opaque(), false,
                                "Inputs.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of("score", Map.of("type", "integer")), List.of()),
                                true,
                                "Score output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("dsl", "transform", Map.of()),
                List.of()
        );
        GraphDraft draft = new GraphDraft(
                "",
                "draft-score",
                3,
                "publishedScore",
                "",
                "",
                "",
                "",
                SchemaEnvelope.object(Map.of("score", Map.of("type", "integer")), List.of("score")),
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
                new GraphDraft.OutputSelection("response", "score"),
                Map.of("response", transformSnapshot.fingerprint())
        );
        String frozenDsl = """
                graph publishedScore {
                  transform response {
                    score = ctx.score
                  }
                }
                """;
        return new VisualGraphPublication(
                "",
                "pub-score",
                draft.draftId(),
                draft.revision(),
                draft.graphName(),
                draft.tenantId(),
                draft.namespace(),
                draft.environment(),
                null,
                draft,
                List.of(transformSnapshot),
                draft.operatorFingerprints(),
                Map.of(),
                frozenDsl,
                new VisualValidationResult(true, List.of()),
                new DslGenerationResult(true, frozenDsl, List.of())
        );
    }

    private static VisualGraphPublication publishedPublicationIdEchoGraph() {
        OperatorDefinition transformSnapshot = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "bloge:transform",
                "1.0.0",
                new OperatorDefinition.Display("Transform", "Published transform snapshot.", List.of("logic")),
                OperatorDefinition.Source.builtIn("bloge-dsl"),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs", SchemaEnvelope.opaque(), false,
                                "Inputs.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of("publicationId", Map.of("type", "string")), List.of()),
                                true,
                                "Publication id echo output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("dsl", "transform", Map.of()),
                List.of()
        );
        GraphDraft draft = new GraphDraft(
                "",
                "draft-publication-id-echo",
                1,
                "publishedPublicationIdEcho",
                "",
                "",
                "",
                "",
                SchemaEnvelope.object(Map.of("publicationId", Map.of("type", "string")), List.of("publicationId")),
                List.of(new GraphDraft.DraftNode(
                        "response",
                        "bloge:transform",
                        "",
                        Map.of("publicationId", GraphDraft.Binding.contextPath("publicationId")),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("response", "publicationId"),
                Map.of("response", transformSnapshot.fingerprint())
        );
        String frozenDsl = """
                graph publishedPublicationIdEcho {
                  transform response {
                    publicationId = ctx.publicationId
                  }
                }
                """;
        return new VisualGraphPublication(
                "",
                "pub-publication-id-echo",
                draft.draftId(),
                draft.revision(),
                draft.graphName(),
                draft.tenantId(),
                draft.namespace(),
                draft.environment(),
                null,
                draft,
                List.of(transformSnapshot),
                draft.operatorFingerprints(),
                Map.of(),
                frozenDsl,
                new VisualValidationResult(true, List.of()),
                new DslGenerationResult(true, frozenDsl, List.of())
        );
    }

    private static ObjectProvider<VisualGraphRunService> providerFor(VisualGraphRunService service) {
        return new ObjectProvider<>() {
            @Override
            public VisualGraphRunService getObject(Object... args) {
                return service;
            }

            @Override
            public VisualGraphRunService getIfAvailable() {
                return service;
            }

            @Override
            public VisualGraphRunService getIfUnique() {
                return service;
            }

            @Override
            public VisualGraphRunService getObject() {
                return service;
            }
        };
    }

    private record NormalizeInput(String raw) {
    }

    private record NormalizeOutput(String value) {
    }

    private static final class NormalizeTextOperator implements Operator<NormalizeInput, NormalizeOutput> {
        @Override
        public NormalizeOutput execute(NormalizeInput input, OperatorContext ctx) {
            return new NormalizeOutput(input.raw().trim().toLowerCase(java.util.Locale.ROOT));
        }
    }
}
