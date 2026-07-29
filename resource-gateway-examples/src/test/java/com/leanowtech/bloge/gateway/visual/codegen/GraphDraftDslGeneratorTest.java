package com.leanowtech.bloge.gateway.visual.codegen;

import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.CompilationMode;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphPublicationOperator;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for lowering visual drafts to BLOGE DSL.
 */
class GraphDraftDslGeneratorTest {

    @Test
    void lowersResourceVirtualOperatorToHttpResourceDsl() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
                VisualCatalogTestSupport.catalogWithLoanApplicantResource());
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "loanPolicy",
                "",
                "",
                "",
                "",
                null,
                List.of(
                        new GraphDraft.DraftNode(
                                "fetchApplicant",
                                "resource:" + VisualCatalogTestSupport.RESOURCE_ID,
                                "",
                                Map.of("applicantId", GraphDraft.Binding.contextPath("applicantId")),
                                Map.of("timeout", "3s", "retryAttempts", 1),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "response",
                                "bloge:transform",
                                "",
                                Map.of(
                                        "applicant", GraphDraft.Binding.nodePath("fetchApplicant", ""),
                                        "score", GraphDraft.Binding.nodePath("fetchApplicant", "score")
                                ),
                                Map.of(),
                                null
                        )
                ),
                List.of(new GraphDraft.DraftEdge("fetch-to-response", "data",
                        new GraphDraft.Endpoint("fetchApplicant", "payload", ""),
                        new GraphDraft.Endpoint("response", "inputs", ""))),
                Map.of(),
                new GraphDraft.OutputSelection("response", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isTrue();
        assertThat(result.dsl()).contains("node fetchApplicant : httpResource");
        assertThat(result.dsl()).contains("resourceId = \"loan-applicant-service.getProfile\"");
        assertThat(result.dsl()).contains("params = { applicantId: ctx.applicantId }");
        assertThat(result.dsl()).contains("score = fetchApplicant.output.payload.score");
    }

    @Test
    void lowersStructuredExecutionConfigExpressions() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
                VisualCatalogTestSupport.catalogWithLoanApplicantResource());
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "dynamicExecutionConfig",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "fetchApplicant",
                        "resource:" + VisualCatalogTestSupport.RESOURCE_ID,
                        "",
                        Map.of("applicantId", GraphDraft.Binding.contextPath("applicantId")),
                        Map.of(
                                "timeout", Map.of("kind", "expression", "expr", "ctx.timeoutBudget"),
                                "retryAttempts", Map.of("kind", "expression", "expr", "ctx.retryAttempts")
                        ),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("fetchApplicant", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isTrue();
        assertThat(result.dsl()).contains("timeout = ctx.timeoutBudget");
        assertThat(result.dsl()).contains("retry = { attempts: ctx.retryAttempts, backoff: 200ms }");
        assertThat(result.dsl()).doesNotContain("kind=expression");
    }

    @Test
    void rejectsDraftIdentifiersThatCannotRenderAsDslIdentifiers() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "graph",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "node",
                        "risk:eligibility",
                        "",
                        Map.of(
                                "score", GraphDraft.Binding.constant(720),
                                "amount", GraphDraft.Binding.constant(1000)
                        ),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("node", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isFalse();
        assertThat(result.diagnostics())
                .filteredOn(diagnostic -> diagnostic.code().startsWith("visual.codegen."))
                .extracting("code", "target")
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("visual.codegen.graphName.invalid", "/graphName"),
                        org.assertj.core.groups.Tuple.tuple("visual.codegen.nodeId.invalid", "/nodes/node/id")
                );
    }

    @Test
    void blocksDesignOnlyOperatorsDuringDslGeneration() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer")));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "schemaOnlyPolicy",
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
                                "score", GraphDraft.Binding.constant(720),
                                "amount", GraphDraft.Binding.constant(1000)
                        ),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("eligibility", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code", "target")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "visual.codegen.designOnlyOperator",
                        "/nodes/eligibility/operatorRef"));
        assertThat(result.diagnostics().getFirst().message())
                .contains("schema-only")
                .contains("lowering.mode=design")
                .contains("validated");
    }

    @Test
    void blocksRemoteWorkerOperatorsDuringDslGeneration() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.remoteWorkerEligibilityLibrary("integer")));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "workerPolicy",
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
                                "score", GraphDraft.Binding.constant(720),
                                "amount", GraphDraft.Binding.constant(1000)
                        ),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("eligibility", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code", "target")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "visual.codegen.runtimeBindingUnsupported",
                        "/nodes/eligibility/operatorRef"));
        assertThat(result.diagnostics().getFirst().message())
                .contains("lowering.mode=remote-worker")
                .contains("DESIGN artifact")
                .contains("cannot execute");
    }

    @Test
    void blocksExternalBoundaryOperatorsDuringDslGeneration() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.externalBoundaryLibrary()));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "eventBoundaryDesign",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "orderEvent",
                        "event:orderSubmitted",
                        "",
                        Map.of(),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("orderEvent", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code", "target")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "visual.codegen.runtimeBindingUnsupported",
                        "/nodes/orderEvent/operatorRef"));
        assertThat(result.diagnostics().getFirst().message())
                .contains("lowering.mode=event-source")
                .contains("DESIGN artifact")
                .contains("cannot execute");
    }

    @Test
    void rejectsBindingPathsThatCannotRenderAsDslPathSegments() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "eligibilityGraph",
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
                                "score", GraphDraft.Binding.contextPath("customer-id"),
                                "amount", GraphDraft.Binding.constant(1000)
                        ),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("eligibility", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isFalse();
        assertThat(result.diagnostics())
                .filteredOn(diagnostic -> diagnostic.code().startsWith("visual.codegen."))
                .extracting("code", "target")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("visual.codegen.pathSegment.invalid",
                                "/nodes/eligibility/inputs/score/path")
                );
    }

    @Test
    void lowersArrayIndexBindingPathsToBracketDsl() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "arrayIndexBindings",
                "",
                "",
                "",
                "",
                null,
                List.of(
                        new GraphDraft.DraftNode(
                                "response",
                                "bloge:transform",
                                "",
                                Map.of("scores", GraphDraft.Binding.contextPath("scores")),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "eligibility",
                                "risk:eligibility",
                                "",
                                Map.of(
                                        "score", GraphDraft.Binding.nodePath("response", "output", "scores.0"),
                                        "amount", GraphDraft.Binding.contextPath("amounts.1.value")
                                ),
                                Map.of(),
                                null
                        )
                ),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("eligibility", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isTrue();
        assertThat(result.dsl()).contains(
                "eligible = response.output.scores[0] >= 700 && ctx.amounts[1].value <= 300000");
        assertThat(result.diagnostics())
                .filteredOn(diagnostic -> diagnostic.code().startsWith("visual.codegen."))
                .isEmpty();
    }

    @Test
    void lowersArrayIndexTemplatePathsToBracketDsl() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
                VisualCatalogTestSupport.catalogWithLibrary(arrayIndexTemplateLibrary()));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "arrayIndexTemplate",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "firstScore",
                        "risk:firstScore",
                        "",
                        Map.of("scores", GraphDraft.Binding.contextPath("scores", "inputs", "scores")),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("firstScore", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isTrue();
        assertThat(result.dsl()).contains("score = ctx.scores[0]");
        assertThat(result.dsl()).doesNotContain("{{");
    }

    @Test
    void lowersUserProvidedTransformOperator() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "eligibilityGraph",
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
                        Map.of("publicationId", "evil-publication", "outputNode", "tamperedOutput"),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("eligibility", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isTrue();
        assertThat(result.dsl()).contains("transform eligibility");
        assertThat(result.dsl()).contains("eligible = ctx.score >= 700 && ctx.amount <= 300000");
        assertThat(result.dsl()).contains("ruleId = \"ELIGIBILITY_V1\"");
    }

    @Test
    void lowersWhitespacePaddedUserTransformTemplates() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        customerOrderMergeLibraryWithTemplates(
                                "{{ input.customer.id }}",
                                "{{ order.id }}")));
        Map<String, GraphDraft.Binding> inputs = new LinkedHashMap<>();
        inputs.put("customer", new GraphDraft.Binding(
                "contextPath",
                null,
                "customer",
                "",
                "",
                "customer",
                "",
                "",
                Map.of()
        ));
        inputs.put("order.id", GraphDraft.Binding.contextPath("orderId", "order", "id"));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "spacedTransformTemplates",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "merge",
                        "risk:customerOrderMerge",
                        "",
                        inputs,
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("merge", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isTrue();
        assertThat(result.dsl()).contains("customerId = ctx.customer.id");
        assertThat(result.dsl()).contains("orderId = ctx.orderId");
        assertThat(result.dsl()).doesNotContain("{{");
    }

    @Test
    void lowersNodePathBindingFromNamedOutputPort() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.multiOutputEligibilityLibrary("integer")));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "namedOutputPort",
                "",
                "",
                "",
                "",
                null,
                List.of(
                        new GraphDraft.DraftNode(
                                "scoreFacts",
                                "risk:scoreFacts",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "eligibility",
                                "risk:eligibility",
                                "",
                                Map.of(
                                        "score", GraphDraft.Binding.nodePath("scoreFacts", "facts", "score"),
                                        "amount", GraphDraft.Binding.contextPath("amount")
                                ),
                                Map.of(),
                                null
                        )
                ),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("eligibility", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isTrue();
        assertThat(result.dsl()).contains("eligible = scoreFacts.output.facts.score >= 700 && ctx.amount <= 300000");
    }

    @Test
    void ordersNodesByImplicitNodePathBindingDependencies() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.multiOutputEligibilityLibrary("integer")));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "implicitDependencyOrder",
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
                                        "score", GraphDraft.Binding.nodePath("scoreFacts", "facts", "score"),
                                        "amount", GraphDraft.Binding.contextPath("amount")
                                ),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "scoreFacts",
                                "risk:scoreFacts",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        )
                ),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("eligibility", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isTrue();
        assertThat(result.dsl().indexOf("node scoreFacts : riskScoreFacts"))
                .isLessThan(result.dsl().indexOf("transform eligibility"));
    }

    @Test
    void ordersNodesByConfigExpressionDependencies() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.multiOutputEligibilityLibrary("integer")));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "configDependencyOrder",
                "",
                "",
                "",
                "",
                null,
                List.of(
                        new GraphDraft.DraftNode(
                                "mapScore",
                                "bloge:transform",
                                "",
                                Map.of(),
                                Map.of("assignments", Map.of("score", "scoreFacts.output.facts.score")),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "scoreFacts",
                                "risk:scoreFacts",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        )
                ),
                List.of(new GraphDraft.DraftEdge("score", "data",
                        new GraphDraft.Endpoint("scoreFacts", "facts", "score"),
                        new GraphDraft.Endpoint("mapScore", "inputs", "score"))),
                Map.of(),
                new GraphDraft.OutputSelection("mapScore", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isTrue();
        assertThat(result.dsl().indexOf("node scoreFacts : riskScoreFacts"))
                .isLessThan(result.dsl().indexOf("transform mapScore"));
        assertThat(result.dsl()).contains("score = scoreFacts.output.facts.score");
    }

    @Test
    void lowersExplicitDependencyEdgesToDependsOnDsl() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.multiOutputEligibilityLibrary("integer")));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "explicitDependencyOrder",
                "",
                "",
                "",
                "",
                null,
                List.of(
                        new GraphDraft.DraftNode(
                                "publishFacts",
                                "risk:scoreFacts",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "prepareFacts",
                                "risk:scoreFacts",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        )
                ),
                List.of(new GraphDraft.DraftEdge("prepare-before-publish", "dependency",
                        new GraphDraft.Endpoint("prepareFacts", "", ""),
                        new GraphDraft.Endpoint("publishFacts", "", ""))),
                Map.of(),
                new GraphDraft.OutputSelection("publishFacts", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isTrue();
        assertThat(result.dsl().indexOf("node prepareFacts : riskScoreFacts"))
                .isLessThan(result.dsl().indexOf("node publishFacts : riskScoreFacts"));
        assertThat(result.dsl()).contains("depends_on = [prepareFacts]");

        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.registerRaw("riskScoreFacts", new StubOperator());
        GraphLoader loader = new GraphLoader(registry);
        loader.withCompilationMode(CompilationMode.LENIENT);
        var compilation = loader.loadWithDiagnostics(result.dsl());
        Graph graph = compilation.graph();
        assertThat(graph)
                .as("compiler diagnostics: %s%nDSL:%n%s", compilation.diagnostics(), result.dsl())
                .isNotNull();
        assertThat(graph.nodes()).isNotEmpty();
    }

    @Test
    void lowersBranchRouteEdgesToBranchDsl() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
                VisualCatalogTestSupport.catalogWithLibrary(VisualCatalogTestSupport.routeLibrary()));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "visualBranchRoute",
                "",
                "",
                "",
                "",
                SchemaEnvelope.object(Map.of(
                        "productType", Map.of("type", "string")
                ), List.of("productType")),
                List.of(
                        new GraphDraft.DraftNode(
                                "physicalFacts",
                                "risk:scoreFacts",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "routeByType",
                                "risk:typeRoute",
                                "",
                                Map.of("value", GraphDraft.Binding.contextPath("productType")),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "genericFacts",
                                "risk:scoreFacts",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        )
                ),
                List.of(
                        new GraphDraft.DraftEdge("route-physical", "route",
                                new GraphDraft.Endpoint("routeByType", "", ""),
                                new GraphDraft.Endpoint("physicalFacts", "", ""),
                                "physical"),
                        new GraphDraft.DraftEdge("route-generic", "route",
                                new GraphDraft.Endpoint("routeByType", "", ""),
                                new GraphDraft.Endpoint("genericFacts", "", ""),
                                "otherwise")
                ),
                Map.of(),
                new GraphDraft.OutputSelection("physicalFacts", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isTrue();
        assertThat(result.dsl().indexOf("branch on routeByType.output.value"))
                .isLessThan(result.dsl().indexOf("node physicalFacts : riskScoreFacts"));
        assertThat(result.dsl())
                .contains("transform routeByType")
                .contains("value = ctx.productType")
                .contains("branch on routeByType.output.value {")
                .contains("\"physical\" -> physicalFacts")
                .contains("otherwise -> genericFacts");

        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.registerRaw("riskScoreFacts", new StubOperator());
        GraphLoader loader = new GraphLoader(registry);
        loader.withCompilationMode(CompilationMode.LENIENT);
        var compilation = loader.loadWithDiagnostics(result.dsl());
        Graph graph = compilation.graph();
        assertThat(graph)
                .as("compiler diagnostics: %s%nDSL:%n%s", compilation.diagnostics(), result.dsl())
                .isNotNull();
        assertThat(graph.nodes()).isNotEmpty();
    }

    @Test
    void lowersWhitespacePaddedBranchSelectorTemplates() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
                VisualCatalogTestSupport.catalogWithLibrary(routeLibraryWithExpression("{{ input.value }}")));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "spacedBranchTemplate",
                "",
                "",
                "",
                "",
                SchemaEnvelope.object(Map.of(
                        "productType", Map.of("type", "string")
                ), List.of("productType")),
                List.of(
                        new GraphDraft.DraftNode(
                                "physicalFacts",
                                "risk:scoreFacts",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "routeByType",
                                "risk:typeRoute",
                                "",
                                Map.of("value", GraphDraft.Binding.contextPath("productType")),
                                Map.of(),
                                null
                        )
                ),
                List.of(new GraphDraft.DraftEdge("route-physical", "route",
                        new GraphDraft.Endpoint("routeByType", "", ""),
                        new GraphDraft.Endpoint("physicalFacts", "", ""),
                        "physical")),
                Map.of(),
                new GraphDraft.OutputSelection("physicalFacts", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isTrue();
        assertThat(result.dsl()).contains("value = ctx.productType");
        assertThat(result.dsl()).contains("\"physical\" -> physicalFacts");
        assertThat(result.dsl()).doesNotContain("{{");
    }

    @Test
    void quotesNamespacedNativeOperatorRefSoGeneratedDslCompiles() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
                VisualCatalogTestSupport.catalogWithLibrary(nativePolicyLibrary("risk:legacyPolicy")));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "namespacedNativePolicy",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "policy",
                        "risk:legacyPolicy",
                        "",
                        Map.of("applicantId", GraphDraft.Binding.contextPath("applicantId")),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("policy", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isTrue();
        assertThat(result.dsl()).contains("node policy : \"risk:legacyPolicy\"");

        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.registerRaw("risk:legacyPolicy", new StubOperator());
        GraphLoader loader = new GraphLoader(registry);
        loader.withCompilationMode(CompilationMode.LENIENT);
        Graph graph = loader.loadWithDiagnostics(result.dsl()).graph();
        assertThat(graph).isNotNull();
        assertThat(graph.nodes()).isNotEmpty();
    }

    @Test
    void lowersNativeNestedInputPathsToObjectLiteralThatCompiles() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
                VisualCatalogTestSupport.catalogWithLibrary(nativeNestedPolicyLibrary()));
        Map<String, GraphDraft.Binding> inputs = new LinkedHashMap<>();
        inputs.put("score", GraphDraft.Binding.contextPath("score", "applicant", "score"));
        inputs.put("segment", GraphDraft.Binding.contextPath("segment", "applicant", "segment"));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "nativeNestedInput",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "policy",
                        "risk:nestedPolicy",
                        "",
                        inputs,
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("policy", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isTrue();
        assertThat(result.dsl()).contains("node policy : riskNestedPolicy");
        assertThat(result.dsl()).contains("applicant = { score: ctx.score, segment: ctx.segment }");

        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.registerRaw("riskNestedPolicy", new StubOperator());
        GraphLoader loader = new GraphLoader(registry);
        loader.withCompilationMode(CompilationMode.LENIENT);
        Graph graph = loader.loadWithDiagnostics(result.dsl()).graph();
        assertThat(graph).isNotNull();
        assertThat(graph.nodes()).isNotEmpty();
    }

    @Test
    void rejectsObjectTemplateKeysThatCannotRenderAsDslObjectFields() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
                VisualCatalogTestSupport.catalogWithLibrary(nativeDynamicObjectPolicyLibrary()));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "nativeDynamicObjectInput",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "policy",
                        "risk:dynamicObjectPolicy",
                        "",
                        Map.of("payload", new GraphDraft.Binding(
                                "objectTemplate",
                                null,
                                "",
                                "",
                                "",
                                "payload",
                                "",
                                "",
                                Map.of("mode", GraphDraft.Binding.expression("ctx.riskMode"))
                        )),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("policy", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.codegen.objectBindingKey.invalid");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/policy/inputs/payload/fields/mode");
                });
    }

    @Test
    void lowersNativeOperatorConfigSchemaValuesAsConfigInputObject() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
                VisualCatalogTestSupport.catalogWithLibrary(nativeConfigurablePolicyLibrary()));
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("threshold", Map.of("kind", "expression", "expr", "ctx.threshold"));
        limits.put("policyMode", "strict");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("limits", limits);
        config.put("enabled", true);
        config.put("timeout", "3s");
        config.put("retryAttempts", 2);
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "nativeConfigInput",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "policy",
                        "risk:configurableNativePolicy",
                        "",
                        Map.of("applicantId", GraphDraft.Binding.contextPath("applicantId")),
                        config,
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("policy", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isTrue();
        assertThat(result.dsl()).contains("node policy : riskConfigurablePolicy");
        assertThat(result.dsl()).contains("applicantId = ctx.applicantId");
        assertThat(result.dsl()).contains("config = { limits: { threshold: ctx.threshold, policyMode: \"strict\" }, enabled: true }");
        assertThat(result.dsl()).contains("timeout = 3s");
        assertThat(result.dsl()).contains("retry = { attempts: 2, backoff: 200ms }");
        assertThat(result.dsl()).doesNotContain("kind =");

        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.registerRaw("riskConfigurablePolicy", new StubOperator());
        GraphLoader loader = new GraphLoader(registry);
        loader.withCompilationMode(CompilationMode.LENIENT);
        var compilation = loader.loadWithDiagnostics(result.dsl());
        Graph graph = compilation.graph();
        assertThat(graph)
                .as("compiler diagnostics: %s%nDSL:%n%s", compilation.diagnostics(), result.dsl())
                .isNotNull();
        assertThat(graph.nodes()).isNotEmpty();
    }

    @Test
    void injectsFrozenPublicationIdForPublishedVisualGraphOperator() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
                VisualCatalogTestSupport.catalogWithLibrary(publicationPolicyLibrary()));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "publishedPolicyComposition",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "publishedEligibility",
                        "publication:pub-eligibility",
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
                new GraphDraft.OutputSelection("publishedEligibility", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isTrue();
        assertThat(result.dsl()).contains("node publishedEligibility : visualPublication");
        assertThat(result.dsl()).contains("score = ctx.score");
        assertThat(result.dsl()).contains("amount = ctx.amount");
        assertThat(result.dsl()).contains("config = { publicationId: \"pub-eligibility\" }");
        assertThat(result.dsl()).doesNotContain("evil-publication");
        assertThat(result.dsl()).doesNotContain("outputNode");

        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.registerRaw(VisualGraphPublicationOperator.NAME, new StubOperator());
        GraphLoader loader = new GraphLoader(registry);
        loader.withCompilationMode(CompilationMode.LENIENT);
        var compilation = loader.loadWithDiagnostics(result.dsl());
        Graph graph = compilation.graph();
        assertThat(graph)
                .as("compiler diagnostics: %s%nDSL:%n%s", compilation.diagnostics(), result.dsl())
                .isNotNull();
        assertThat(graph.nodes()).isNotEmpty();
    }

    @Test
    void rejectsNativeOperatorConfigKeysThatCannotRenderAsDslFields() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
                VisualCatalogTestSupport.catalogWithLibrary(nativePolicyLibrary("riskPolicy")));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "nativeConfigKeyword",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "policy",
                        "risk:legacyPolicy",
                        "",
                        Map.of("applicantId", GraphDraft.Binding.contextPath("applicantId")),
                        Map.of("mode", "strict"),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("policy", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.codegen.configKey.invalid");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/policy/config/mode");
                });
    }

    @Test
    void rejectsTransformAssignmentKeysThatCannotRenderAsDslFields() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "transformKeywordAssignment",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "mapResult",
                        "bloge:transform",
                        "",
                        Map.of(),
                        Map.of("assignments", Map.of("mode", "\"strict\"")),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("mapResult", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.codegen.transformAssignmentKey.invalid");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/mapResult/config/assignments/mode");
                });
    }

    @Test
    void rejectsDecisionTableKeysThatCannotRenderAsDslFields() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "decisionTableKeywordOutput",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "decision",
                        "bloge:decisionTable",
                        "",
                        Map.of(),
                        Map.of(
                                "inputs", Map.of("mode", "score"),
                                "rules", List.of(Map.of(
                                        "conditions", "score: score >= 700",
                                        "output", Map.of(
                                                "customer-id", "C-1",
                                                "otherwise", false,
                                                "details", Map.of("risk-band", "low")
                                        )
                                ))
                        ),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("decision", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isFalse();
        assertThat(result.diagnostics())
                .filteredOn(diagnostic -> diagnostic.code().startsWith("visual.codegen.decisionTable"))
                .extracting("target")
                .containsExactlyInAnyOrder(
                        "/nodes/decision/config/inputs/mode",
                        "/nodes/decision/config/rules/0/output/customer-id",
                        "/nodes/decision/config/rules/0/output/otherwise",
                        "/nodes/decision/config/rules/0/output/details/risk-band"
                );
    }

    @Test
    void rejectsDuplicateNativeInputLeafPathsDuringCodegen() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
                VisualCatalogTestSupport.catalogWithLibrary(nativeNestedPolicyLibrary()));
        Map<String, GraphDraft.Binding> inputs = new LinkedHashMap<>();
        inputs.put("scoreFromContext", GraphDraft.Binding.contextPath("score", "applicant", "score"));
        inputs.put("scoreOverride", GraphDraft.Binding.contextPath("overrideScore", "applicant", "score"));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "duplicateNativeInput",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "policy",
                        "risk:nestedPolicy",
                        "",
                        inputs,
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("policy", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.code())
                        .isEqualTo("visual.codegen.inputPath.duplicate"));
    }

    @Test
    void lowersPortQualifiedInputTemplateAliases() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.duplicateInputPathLibrary()));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "duplicateInputPath",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "merge",
                        "risk:customerOrderMerge",
                        "",
                        Map.of(
                                "customer.id", GraphDraft.Binding.contextPath("customerId", "customer", "id"),
                                "order.id", GraphDraft.Binding.contextPath("orderId", "order", "id")
                        ),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("merge", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isTrue();
        assertThat(result.dsl()).contains("customerId = ctx.customerId");
        assertThat(result.dsl()).contains("orderId = ctx.orderId");
    }

    @Test
    void lowersRootPortBindingTemplateDescendants() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.duplicateInputPathLibrary()));
        Map<String, GraphDraft.Binding> inputs = new LinkedHashMap<>();
        inputs.put("", new GraphDraft.Binding(
                "contextPath",
                null,
                "customer",
                "",
                "",
                "customer",
                "",
                "",
                Map.of()
        ));
        inputs.put("order.id", GraphDraft.Binding.contextPath("orderId", "order", "id"));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "rootPortInput",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "merge",
                        "risk:customerOrderMerge",
                        "",
                        inputs,
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("merge", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isTrue();
        assertThat(result.dsl()).contains("customerId = ctx.customer.id");
        assertThat(result.dsl()).contains("orderId = ctx.orderId");
    }

    @Test
    void lowersRootPortBindingWhenStorageKeyIsPortName() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.duplicateInputPathLibrary()));
        Map<String, GraphDraft.Binding> inputs = new LinkedHashMap<>();
        inputs.put("customer", new GraphDraft.Binding(
                "contextPath",
                null,
                "customer",
                "",
                "",
                "customer",
                "",
                "",
                Map.of()
        ));
        inputs.put("order.id", GraphDraft.Binding.contextPath("orderId", "order", "id"));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "rootPortInput",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "merge",
                        "risk:customerOrderMerge",
                        "",
                        inputs,
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("merge", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isTrue();
        assertThat(result.dsl()).contains("customerId = ctx.customer.id");
        assertThat(result.dsl()).contains("orderId = ctx.orderId");
    }

    @Test
    void lowersRootPortBindingFromNamedNodeOutputPort() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.rootObjectPortLibrary()));
        Map<String, GraphDraft.Binding> inputs = new LinkedHashMap<>();
        inputs.put("customer", GraphDraft.Binding.nodePath(
                "customerFacts",
                "customer",
                "",
                "customer",
                ""));
        inputs.put("order.id", GraphDraft.Binding.contextPath("orderId", "order", "id"));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "nodeRootPortInput",
                "",
                "",
                "",
                "",
                null,
                List.of(
                        new GraphDraft.DraftNode(
                                "customerFacts",
                                "risk:customerFacts",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "merge",
                                "risk:customerOrderMerge",
                                "",
                                inputs,
                                Map.of(),
                                null
                        )
                ),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("merge", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isTrue();
        assertThat(result.dsl()).contains("customerId = customerFacts.output.customer.id");
        assertThat(result.dsl()).contains("orderId = ctx.orderId");
    }

    @Test
    void lowersNestedInputTemplateAlias() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.nestedApplicantEligibilityLibrary()));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "nestedInputPath",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "eligibility",
                        "risk:nestedApplicantEligibility",
                        "",
                        Map.of(
                                "applicant.score",
                                GraphDraft.Binding.contextPath("score", "inputs", "applicant.score")
                        ),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("eligibility", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isTrue();
        assertThat(result.dsl()).contains("eligible = ctx.score >= 700");
    }

    @Test
    void lowersGraphInputButKeepsSelectedPayloadContractOutOfDslTerminalSchema() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        SchemaEnvelope outputSchema = SchemaEnvelope.object(Map.of(
                "decision", Map.of("type", "string"),
                "score", Map.of("type", "integer"),
                "review", Map.of(
                        "type", "object",
                        "properties", Map.of("required", Map.of("type", "boolean")),
                        "required", List.of("required")
                )
        ), List.of("decision", "score"));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "schemaBoundary",
                "",
                "",
                "",
                "",
                SchemaEnvelope.object(Map.of(
                        "applicantId", Map.of("type", "string"),
                        "requestedAmount", Map.of("type", "number")
                ), List.of("applicantId")),
                List.of(new GraphDraft.DraftNode(
                        "response",
                        "bloge:transform",
                        "",
                        Map.of(),
                        Map.of("assignments", List.of(
                                Map.of("field", "decision", "expression", "\"approve\""),
                                Map.of("field", "score", "expression", "720"),
                                Map.of("field", "review", "expression", "{ required: false }")
                        )),
                        null
                )),
                List.of(),
                Map.of("graphContract", Map.of("outputSchema", outputSchema)),
                new GraphDraft.OutputSelection("response", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isTrue();
        assertThat(result.dsl())
                .contains("graph schemaBoundary")
                .contains("input {")
                .contains("applicantId: String")
                .contains("requestedAmount: Decimal?")
                .doesNotContain("output {");

        List<String> compilerWarnings = new ArrayList<>();
        Logger compilerLogger = Logger.getLogger("com.leanowtech.bloge.dsl.compiler.DslCompiler");
        Handler warningCapture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record != null && record.getMessage() != null) {
                    compilerWarnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        compilerLogger.addHandler(warningCapture);
        try {
            GraphLoader loader = new GraphLoader(new DefaultOperatorRegistry());
            loader.withCompilationMode(CompilationMode.LENIENT);
            var compilation = loader.loadWithDiagnostics(result.dsl());
            assertThat(compilation.graph())
                    .as("compiler diagnostics: %s%nDSL:%n%s", compilation.diagnostics(), result.dsl())
                    .isNotNull();
        } finally {
            compilerLogger.removeHandler(warningCapture);
        }
        assertThat(compilerWarnings)
                .as("the selected payload contract is validated by the visual runtime, not DSL terminal aggregation")
                .noneMatch(message -> message.contains("declared output"));
    }

    private static OperatorLibrary arrayIndexTemplateLibrary() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:firstScore",
                "1.0.0",
                new OperatorDefinition.Display("First score", "Selects the first score from an input array.",
                        List.of("risk", "array")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(Map.of(
                                        "scores", Map.of("type", "array", "items", Map.of("type", "integer"))
                                ), List.of("scores")),
                                true,
                                "Array score input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of("score", Map.of("type", "integer")), List.of()),
                                true,
                                "Selected score."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("transform", "transform", Map.of(
                        "assignments", Map.of("score", "{{input.scores.0}}")
                )),
                List.of()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-array-templates",
                "Array template operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );
    }

    private static OperatorLibrary nativePolicyLibrary(String executableOperatorRef) {
        Map<String, Object> inputProperties = Map.of("applicantId", Map.of("type", "string"));
        Map<String, Object> outputProperties = Map.of("decision", Map.of("type", "string"));
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:legacyPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Legacy policy", "Delegates to a runtime policy operator.",
                        List.of("risk", "legacy")),
                new OperatorDefinition.Source("user-library", "", "", "", false),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(inputProperties, List.of("applicantId")),
                                true,
                                "Policy inputs.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(outputProperties, List.of()),
                                true,
                                "Policy output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", executableOperatorRef, Map.of()),
                List.of()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-native-policy",
                "Risk native policy operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );
    }

    private static OperatorLibrary nativeConfigurablePolicyLibrary() {
        Map<String, Object> inputProperties = Map.of("applicantId", Map.of("type", "string"));
        Map<String, Object> outputProperties = Map.of("decision", Map.of("type", "string"));
        Map<String, Object> limitsProperties = new LinkedHashMap<>();
        limitsProperties.put("threshold", Map.of("type", "integer"));
        limitsProperties.put("policyMode", Map.of(
                "type", "enum",
                "values", List.of("strict", "relaxed")
        ));
        Map<String, Object> configProperties = new LinkedHashMap<>();
        configProperties.put("limits", Map.of(
                "type", "object",
                "properties", limitsProperties,
                "required", List.of("threshold", "policyMode"),
                "additionalProperties", false
        ));
        configProperties.put("enabled", Map.of("type", "boolean"));

        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:configurableNativePolicy",
                "1.0.0",
                new OperatorDefinition.Display("Configurable native policy",
                        "Delegates runtime policy decisions with config.",
                        List.of("risk", "config")),
                new OperatorDefinition.Source("user-library", "", "", "", false),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(inputProperties, List.of("applicantId")),
                                true,
                                "Policy inputs.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(outputProperties, List.of()),
                                true,
                                "Policy output."))
                ),
                SchemaEnvelope.object(configProperties, List.of("limits")),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskConfigurablePolicy", Map.of()),
                List.of()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-native-configurable-policy",
                "Risk native configurable policy operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );
    }

    private static OperatorLibrary publicationPolicyLibrary() {
        Map<String, Object> inputProperties = new LinkedHashMap<>();
        inputProperties.put("score", Map.of("type", "integer"));
        inputProperties.put("amount", Map.of("type", "number"));
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "publication:pub-eligibility",
                "7",
                new OperatorDefinition.Display("Published eligibility",
                        "Invokes a frozen published visual graph.",
                        List.of("publication", "subgraph")),
                new OperatorDefinition.Source("visual-publication", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(inputProperties, List.of("score", "amount")),
                                true,
                                "Published graph inputs.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of("eligible", Map.of("type", "boolean")), List.of()),
                                true,
                                "Published graph output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", VisualGraphPublicationOperator.NAME,
                        Map.of("publicationId", "pub-eligibility")),
                List.of()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "published-visual-graphs",
                "Published visual graphs",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );
    }

    private static OperatorLibrary nativeNestedPolicyLibrary() {
        Map<String, Object> applicantProperties = new LinkedHashMap<>();
        applicantProperties.put("score", Map.of("type", "integer"));
        applicantProperties.put("segment", Map.of("type", "string"));
        Map<String, Object> outputProperties = Map.of("decision", Map.of("type", "string"));
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:nestedPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Nested policy", "Delegates nested applicant facts to runtime.",
                        List.of("risk", "nested")),
                new OperatorDefinition.Source("user-library", "", "", "", false),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("applicant",
                                SchemaEnvelope.object(applicantProperties, List.of("score", "segment")),
                                true,
                                "Applicant facts.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(outputProperties, List.of()),
                                true,
                                "Policy output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskNestedPolicy", Map.of()),
                List.of()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-native-nested-policy",
                "Risk native nested policy operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );
    }

    private static OperatorLibrary nativeDynamicObjectPolicyLibrary() {
        Map<String, Object> payloadSchema = new LinkedHashMap<>();
        payloadSchema.put("type", "object");
        payloadSchema.put("additionalProperties", true);
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:dynamicObjectPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Dynamic object policy",
                        "Accepts dynamic payload fields.",
                        List.of("risk", "dynamic")),
                new OperatorDefinition.Source("user-library", "", "", "", false),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", payloadSchema),
                                true,
                                "Dynamic payload.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of("decision", Map.of("type", "string")), List.of()),
                                true,
                                "Policy output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskDynamicObjectPolicy", Map.of()),
                List.of()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-dynamic-object-policy",
                "Risk dynamic object policy operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );
    }

    private static OperatorLibrary customerOrderMergeLibraryWithTemplates(String customerTemplate,
                                                                          String orderTemplate) {
        OperatorDefinition base = VisualCatalogTestSupport.customerOrderMergeOperator();
        OperatorDefinition operator = operatorWithLowering(base,
                new OperatorDefinition.Lowering("transform", "transform", Map.of(
                        "assignments", Map.of(
                                "customerId", customerTemplate,
                                "orderId", orderTemplate
                        )
                )));
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-duplicate-inputs",
                "Duplicate input path operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );
    }

    private static OperatorLibrary routeLibraryWithExpression(String expression) {
        OperatorDefinition operator = operatorWithLowering(VisualCatalogTestSupport.typeRouteOperator(),
                new OperatorDefinition.Lowering("branch", "branch", Map.of("expression", expression)));
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-routes",
                "Risk route operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator, VisualCatalogTestSupport.scoreFactsOperator())
        );
    }

    private static OperatorDefinition operatorWithLowering(OperatorDefinition operator,
                                                           OperatorDefinition.Lowering lowering) {
        return new OperatorDefinition(
                operator.schemaVersion(),
                operator.operatorRef(),
                operator.operatorVersion(),
                operator.display(),
                operator.source(),
                operator.ports(),
                operator.configSchema(),
                operator.capabilities(),
                operator.policy(),
                lowering,
                operator.diagnostics()
        );
    }

    private static class StubOperator implements Operator<Object, Object> {
        @Override
        public Object execute(Object input, OperatorContext ctx) {
            return null;
        }
    }
}
