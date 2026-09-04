package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies the public, scoped and payload-free DSL authoring reference seam. */
class AgentDslAuthoringSupportTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void emptyLibraryRefsExposeBuiltInsAndScopedResourcesWithoutRuntimeMaterial() {
        OperatorDefinition builtIn = operator("bloge:transform", "", OperatorDefinition.Policy.unrestricted());
        OperatorDefinition library = operator("ride:lookup", "ride-policy", OperatorDefinition.Policy.unrestricted());
        OperatorDefinition resource = resourceOperator("resource:ride-order.get");
        AgentDslAuthoringSupport support = support(List.of(builtIn, library, resource), List.of());

        DslReferenceSnapshot reference = support.reference(
                new DslReferenceRequest(List.of(), List.of("graph", "bindings"),
                        List.of(), true), identity("project-a"));

        assertThat(reference.schemaVersion()).isEqualTo("rg.dslReference.v1");
        assertThat(reference.supportedRootKinds()).containsExactly("graph");
        assertThat(reference.operators()).extracting(DslReferenceSnapshot.OperatorContract::operatorRef)
                .containsExactly("bloge:transform", "resource:ride-order.get");
        assertThat(reference.authoringContextFingerprint()).startsWith("sha256:");
        assertThat(mapper.valueToTree(reference).toString())
                .doesNotContain("secret.internal", "unsafe operator description", "business-example-value",
                        "urlTemplate", "diagnostics", "lowering");
    }

    @Test
    void selectedLibraryAndOperatorFiltersAreScopeBoundAndFingerprintStable() {
        OperatorDefinition builtIn = operator("bloge:transform", "", OperatorDefinition.Policy.unrestricted());
        OperatorDefinition visible = operator("ride:lookup", "ride-policy",
                new OperatorDefinition.Policy(List.of("tenant-a"), List.of("project-a"), List.of("test")));
        OperatorDefinition otherProject = operator("ride:hidden", "ride-policy",
                new OperatorDefinition.Policy(List.of("tenant-a"), List.of("project-b"), List.of("test")));
        OperatorLibrary library = library("ride-policy", List.of(visible, otherProject));
        AgentDslAuthoringSupport support = support(List.of(otherProject, visible, builtIn), List.of(library));

        DslReferenceSnapshot first = support.reference(
                new DslReferenceRequest(List.of("ride-policy"), List.of("bindings", "graph"),
                        List.of("ride:lookup", "bloge:transform"), false), identity("project-a"));
        DslReferenceSnapshot reordered = support.reference(
                new DslReferenceRequest(List.of("ride-policy"), List.of("graph", "bindings"),
                        List.of("bloge:transform", "ride:lookup"), false), identity("project-a"));

        assertThat(first.operators()).extracting(DslReferenceSnapshot.OperatorContract::operatorRef)
                .containsExactly("bloge:transform", "ride:lookup");
        assertThat(first.operators()).extracting(DslReferenceSnapshot.OperatorContract::operatorRef)
                .doesNotContain("ride:hidden");
        assertThat(first.authoringContextFingerprint()).isEqualTo(reordered.authoringContextFingerprint());
        assertThat(first.referenceVersion()).isEqualTo(reordered.referenceVersion());
        assertThat(first.examples()).isEmpty();
        assertThat(mapper.valueToTree(first).toString())
                .doesNotContain("secret.internal", "business-example-value", "urlTemplate", "description");
    }

    @Test
    void treatsAnExistingLibraryWithOnlyOtherProjectOperatorsAsNotVisible() {
        OperatorDefinition otherProject = operator("ride:hidden", "ride-policy",
                new OperatorDefinition.Policy(List.of("tenant-a"), List.of("project-b"), List.of("test")));
        AgentDslAuthoringSupport support = support(
                List.of(otherProject), List.of(library("ride-policy", List.of(otherProject))));

        assertThatThrownBy(() -> support.reference(new DslReferenceRequest(
                List.of("ride-policy"), List.of(), List.of(), false), identity("project-a")))
                .isInstanceOfSatisfying(AgentTddToolException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("DSL_LIBRARY_NOT_VISIBLE");
                    assertThat(failure.details()).containsOnlyKeys("nextAction");
                });
    }

    @Test
    void freezesLibraryOperatorsAndFunctionsFromOneRegistrySnapshot() {
        OperatorDefinition firstOperator = operator(
                "ride:first", "ride-policy", OperatorDefinition.Policy.unrestricted());
        OperatorDefinition laterOperator = operator(
                "ride:later", "ride-policy", OperatorDefinition.Policy.unrestricted());
        OperatorLibrary first = library("ride-policy", List.of(firstOperator));
        OperatorLibrary later = library("ride-policy", List.of(laterOperator));
        AtomicInteger reads = new AtomicInteger();
        OperatorLibraryRegistry changing = new FixedLibraries(List.of(first)) {
            @Override public Collection<OperatorLibrary> all() {
                return reads.getAndIncrement() == 0 ? List.of(first) : List.of(later);
            }

            @Override public Optional<OperatorLibrary> find(String libraryId) {
                throw new AssertionError("authoring context must not re-read a mutable library registry");
            }
        };
        AgentDslAuthoringSupport support = new AgentDslAuthoringSupport(
                new FixedCatalog(List.of()), changing, mapper);

        DslReferenceSnapshot reference = support.reference(new DslReferenceRequest(
                List.of("ride-policy"), List.of(), List.of(), false), identity("project-a"));

        assertThat(reads).hasValue(1);
        assertThat(reference.operators()).extracting(DslReferenceSnapshot.OperatorContract::operatorRef)
                .contains("ride:first").doesNotContain("ride:later");
        assertThat(reference.functions()).extracting(DslReferenceSnapshot.FunctionContract::name)
                .contains("rideRisk");
    }

    @Test
    void contractLensPreservesOnlyLocalSchemaReferencesAndTheirDefinitions() {
        SchemaEnvelope schema = new SchemaEnvelope("json-schema", "2020-12", Map.of(
                "type", "object",
                "$defs", Map.of("Node", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "next", Map.of("$ref", "#/$defs/Node"),
                                "remote", Map.of("$ref", "https://secret.internal/schema")))),
                "properties", Map.of("root", Map.of("$ref", "#/$defs/Node"))));

        Map<String, Object> projected = new DslContractLens(mapper).schema(schema);
        String json = mapper.valueToTree(projected).toString();

        assertThat(json).contains("$defs", "#/\u0024defs/Node");
        assertThat(json).doesNotContain("https://", "secret.internal");
    }

    @Test
    void rejectsUnknownDisabledAndOversizedReferenceRequestsWithoutCatalogDisclosure() {
        AgentDslAuthoringSupport support = support(List.of(), List.of());

        assertThatThrownBy(() -> support.reference(
                new DslReferenceRequest(List.of("not-visible"), List.of(), List.of(), false), identity("project-a")))
                .isInstanceOf(AgentTddToolException.class)
                .extracting(error -> ((AgentTddToolException) error).code())
                .isEqualTo("DSL_LIBRARY_NOT_VISIBLE");
        assertThatThrownBy(() -> support.reference(
                new DslReferenceRequest(List.of(), java.util.stream.IntStream.range(0, 21)
                        .mapToObj(index -> "topic-" + index).toList(), List.of(), false), identity("project-a")))
                .isInstanceOf(AgentTddToolException.class)
                .extracting(error -> ((AgentTddToolException) error).code())
                .isEqualTo("DSL_REFERENCE_TOO_LARGE");
    }

    @Test
    void returnsSafeParseGuidanceThenAcceptsTheCorrectedCandidate() {
        OperatorDefinition transform = operator(
                "bloge:transform", "", OperatorDefinition.Policy.unrestricted());
        AgentDslAuthoringSupport support = support(List.of(transform), List.of());
        DslReferenceSnapshot reference = support.reference(
                new DslReferenceRequest(List.of(), List.of(), List.of(), true), identity("project-a"));

        DslPreviewReceipt rejected = support.preview(new DslPreviewRequest(
                "candidate.bloge",
                "graph broken { transform result { value = ctx.token }",
                List.of(), reference.authoringContextFingerprint()), identity("project-a"));

        assertThat(rejected.technicalAcceptance()).isEqualTo("REVISE");
        assertThat(rejected.stages()).extracting(DslPreviewReceipt.Stage::phase, DslPreviewReceipt.Stage::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("CONTEXT", "PASS"),
                        org.assertj.core.groups.Tuple.tuple("PARSE", "FAIL"),
                        org.assertj.core.groups.Tuple.tuple("RESOLVE", "NOT_RUN"),
                        org.assertj.core.groups.Tuple.tuple("TYPE_CHECK", "NOT_RUN"),
                        org.assertj.core.groups.Tuple.tuple("SEMANTIC_COMPILE", "NOT_RUN"),
                        org.assertj.core.groups.Tuple.tuple("LINT", "NOT_RUN"),
                        org.assertj.core.groups.Tuple.tuple("PROJECT", "NOT_RUN"),
                        org.assertj.core.groups.Tuple.tuple("ROUND_TRIP", "NOT_RUN"));
        assertThat(rejected.authoringDiagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.phase()).isEqualTo("PARSE");
            assertThat(diagnostic.code()).isEqualTo("DSL_PARSE_EXPECTED_CONSTRUCT");
            assertThat(diagnostic.safeSummary()).doesNotContain("token", "broken", "ctx");
            assertThat(diagnostic.referenceRefs()).contains("topic:graph");
            assertThat(diagnostic.resolutionClass()).isEqualTo("AGENT_CAN_REVISE");
        });

        DslReferenceSnapshot.Example example = reference.examples().stream()
                .filter(value -> value.exampleId().equals("graph-transform-minimal"))
                .findFirst().orElseThrow();
        assertThat(example.exampleFingerprint()).startsWith("sha256:");
        DslPreviewReceipt accepted = support.preview(new DslPreviewRequest(
                example.exampleId(), example.source(), List.of(),
                reference.authoringContextFingerprint()), identity("project-a"));

        assertThat(accepted.accepted()).isTrue();
        assertThat(accepted.technicalAcceptance()).isEqualTo("ACCEPTED");
        assertThat(accepted.stages()).extracting(DslPreviewReceipt.Stage::status)
                .containsOnly("PASS");
        assertThat(accepted.authoringReceiptFingerprint()).startsWith("sha256:");
    }

    @Test
    void suggestsOnlyVisibleOperatorsAndNeverEchoesRejectedSourceOrLowerMessages() {
        OperatorDefinition transform = operator(
                "bloge:transform", "", OperatorDefinition.Policy.unrestricted());
        OperatorDefinition hidden = operator("secret:operator", "secret-library",
                OperatorDefinition.Policy.unrestricted());
        AgentDslAuthoringSupport support = support(List.of(transform, hidden), List.of());
        String context = support.reference(new DslReferenceRequest(
                List.of(), List.of(), List.of(), false), identity("project-a"))
                .authoringContextFingerprint();

        DslPreviewReceipt receipt = support.preview(new DslPreviewRequest(
                "candidate.bloge",
                "graph candidate { node lookup : \"bloge:tranform\" { } } // token=secret-value",
                List.of(), context), identity("project-a"));

        DslAuthoringDiagnostic diagnostic = receipt.authoringDiagnostics().stream()
                .filter(value -> value.code().equals("DSL_OPERATOR_NOT_FOUND"))
                .findFirst().orElseThrow();
        assertThat(diagnostic.phase()).isEqualTo("RESOLVE");
        assertThat(diagnostic.fixHints()).extracting(DslAuthoringDiagnostic.FixHint::candidate)
                .contains("bloge:transform")
                .doesNotContain("secret:operator");
        assertThat(mapper.valueToTree(receipt).toString())
                .doesNotContain("tranform", "secret-value", "secret:operator", "token=");
    }

    @Test
    void mapsDecisionTableLintRulesWithoutPassingThroughRuleMessages() {
        AgentDslAuthoringSupport support = support(List.of(
                operator("bloge:decisionTable", "", OperatorDefinition.Policy.unrestricted())), List.of());
        String context = support.reference(new DslReferenceRequest(
                List.of(), List.of(), List.of(), false), identity("project-a"))
                .authoringContextFingerprint();
        String source = """
                graph policyGraph {
                  decision_table policy(score = ctx.score) hit=unique -> { decision: String } {
                    rule (score: score >= 100) -> { decision: "review-secret" }
                    rule (score: score >= 120) -> { decision: "accept-secret" }
                  }
                }
                """;

        DslPreviewReceipt receipt = support.preview(new DslPreviewRequest(
                "policy.bloge", source, List.of(), context), identity("project-a"));

        assertThat(receipt.authoringDiagnostics()).extracting(DslAuthoringDiagnostic::code)
                .contains("DSL_DECISION_UNIQUE_OVERLAP", "DSL_DECISION_OTHERWISE_REQUIRED");
        assertThat(receipt.authoringDiagnostics().stream()
                .filter(value -> value.code().startsWith("DSL_DECISION_")))
                .allMatch(value -> value.phase().equals("SEMANTIC_COMPILE"));
        assertThat(mapper.valueToTree(receipt).toString())
                .doesNotContain("review-secret", "accept-secret", "policyGraph");
    }

    @Test
    void classifiesReservedDeclarationIdentifiersWithoutEchoingTheKeyword() {
        AgentDslAuthoringSupport support = support(List.of(), List.of());
        String context = support.reference(new DslReferenceRequest(
                List.of(), List.of(), List.of(), false), identity("project-a"))
                .authoringContextFingerprint();

        DslPreviewReceipt receipt = support.preview(new DslPreviewRequest(
                "candidate.bloge", "graph graph {}", List.of(), context), identity("project-a"));

        assertThat(receipt.authoringDiagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("DSL_IDENTIFIER_RESERVED");
            assertThat(diagnostic.phase()).isEqualTo("PARSE");
            assertThat(diagnostic.expectedKinds()).containsExactly("IDENTIFIER");
            assertThat(diagnostic.safeSummary()).doesNotContain("graph graph");
        });
    }

    @Test
    void withholdsLowConfidenceOperatorSuggestions() {
        AgentDslAuthoringSupport support = support(List.of(
                operator("bloge:transform", "", OperatorDefinition.Policy.unrestricted()),
                operator("bloge:decisionTable", "", OperatorDefinition.Policy.unrestricted())), List.of());
        String context = support.reference(new DslReferenceRequest(
                List.of(), List.of(), List.of(), false), identity("project-a"))
                .authoringContextFingerprint();

        DslPreviewReceipt receipt = support.preview(new DslPreviewRequest(
                "candidate.bloge", "graph candidate { node value : \"unrelated:opaqueThing\" {} }",
                List.of(), context), identity("project-a"));

        assertThat(receipt.authoringDiagnostics().stream()
                .filter(value -> value.code().equals("DSL_OPERATOR_NOT_FOUND")))
                .singleElement().satisfies(diagnostic -> assertThat(diagnostic.fixHints()).isEmpty());
    }

    @Test
    void rejectsMissingAndStaleContextBeforeCompilingAnySource() {
        AgentDslAuthoringSupport support = support(List.of(
                operator("bloge:transform", "", OperatorDefinition.Policy.unrestricted())), List.of());

        assertThatThrownBy(() -> support.preview(new DslPreviewRequest(
                "candidate.bloge", "payload-marker", List.of(), ""), identity("project-a")))
                .isInstanceOf(AgentTddToolException.class)
                .extracting(error -> ((AgentTddToolException) error).code())
                .isEqualTo("DSL_AUTHORING_CONTEXT_REQUIRED");
        assertThatThrownBy(() -> support.preview(new DslPreviewRequest(
                "candidate.bloge", "payload-marker", List.of(), "sha256:stale"), identity("project-a")))
                .isInstanceOf(AgentTddToolException.class)
                .extracting(error -> ((AgentTddToolException) error).code())
                .isEqualTo("DSL_AUTHORING_CONTEXT_STALE");
    }

    @Test
    void keepsParseCoordinatesStructuredAndFingerprintsOpaque() {
        AgentDslAuthoringSupport support = support(List.of(
                operator("bloge:transform", "", OperatorDefinition.Policy.unrestricted())), List.of());
        String context = support.reference(new DslReferenceRequest(
                List.of(), List.of(), List.of(), false), identity("project-a"))
                .authoringContextFingerprint();

        DslPreviewReceipt receipt = support.preview(new DslPreviewRequest(
                "candidate.bloge", "graph candidate {\n  transform result {\n    value =\n}",
                List.of(), context), identity("project-a"));

        assertThat(receipt.authoringDiagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.span().startLine()).isGreaterThanOrEqualTo(0);
            assertThat(diagnostic.span().startColumn()).isGreaterThanOrEqualTo(0);
            if (!diagnostic.span().known()) {
                assertThat(diagnostic.span().startLine()).isZero();
                assertThat(diagnostic.span().startColumn()).isZero();
            }
            assertThat(diagnostic.diagnosticFingerprint()).matches("sha256:[0-9a-f]{64}");
        });
        assertThat(receipt.projection().get("sourceSemanticFingerprint")).isEqualTo("");
    }

    @Test
    void rejectsOversizedSourceBeforeParserAllocation() {
        AgentDslAuthoringSupport support = support(List.of(), List.of());
        String context = support.reference(new DslReferenceRequest(
                List.of(), List.of(), List.of(), false), identity("project-a"))
                .authoringContextFingerprint();

        assertThatThrownBy(() -> support.preview(new DslPreviewRequest(
                "candidate.bloge", "x".repeat(DslAuthoringCompiler.MAX_SOURCE_BYTES + 1),
                List.of(), context), identity("project-a")))
                .isInstanceOf(AgentTddToolException.class)
                .extracting(error -> ((AgentTddToolException) error).code())
                .isEqualTo("DSL_SOURCE_TOO_LARGE");
    }

    @Test
    void cancelsAndClassifiesACompilerThatExceedsThePreviewBudget() {
        AgentDslAuthoringSupport support = new AgentDslAuthoringSupport(
                new FixedCatalog(List.of()), new FixedLibraries(List.of()), mapper,
                (request, context) -> {
                    try {
                        Thread.sleep(Duration.ofSeconds(30));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    throw new IllegalStateException("late compiler payload");
                }, Duration.ofMillis(25));
        String context = support.reference(new DslReferenceRequest(
                List.of(), List.of(), List.of(), false), identity("project-a"))
                .authoringContextFingerprint();

        assertThatThrownBy(() -> support.preview(new DslPreviewRequest(
                "candidate.bloge", "graph candidate {}", List.of(), context), identity("project-a")))
                .isInstanceOfSatisfying(AgentTddToolException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("DSL_PREVIEW_TIMEOUT");
                    assertThat(failure.retryable()).isTrue();
                    assertThat(failure.getMessage()).doesNotContain("payload");
                });
    }

    private AgentDslAuthoringSupport support(List<OperatorDefinition> operators,
                                             List<OperatorLibrary> libraries) {
        return new AgentDslAuthoringSupport(new FixedCatalog(operators), new FixedLibraries(libraries), mapper);
    }

    private static OperatorDefinition operator(String ref,
                                               String libraryId,
                                               OperatorDefinition.Policy policy) {
        return new OperatorDefinition(
                "bloge.visualOperator.v1", ref, "1.0.0", "",
                new OperatorDefinition.Display(ref, "unsafe operator description", List.of("unsafe")),
                new OperatorDefinition.Source(libraryId.isBlank() ? "bloge-dsl" : "user-library",
                        libraryId.isBlank() ? "" : "resource-secret", "GET",
                        libraryId.isBlank() ? "" : "https://secret.internal/business-example-value",
                        false, libraryId),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("input", SchemaEnvelope.object(Map.of(
                                "secretField", Map.of("type", "string", "description", "business-example-value",
                                        "default", "secret")), List.of("secretField")), true, "unsafe")),
                        List.of(new OperatorDefinition.Port("output", SchemaEnvelope.opaque(), true, "unsafe"))),
                SchemaEnvelope.object(Map.of("mode", Map.of("type", "string", "default", "secret")), List.of()),
                OperatorDefinition.Capabilities.pure(), policy,
                new OperatorDefinition.Lowering(libraryId.isBlank() ? "dsl" : "design", ref,
                        Map.of("bindingRef", "secret-binding")), List.of());
    }

    private static OperatorDefinition resourceOperator(String ref) {
        OperatorDefinition base = operator(ref, "", OperatorDefinition.Policy.unrestricted());
        return new OperatorDefinition(base.schemaVersion(), base.operatorRef(), base.operatorVersion(),
                base.display(), new OperatorDefinition.Source("resource", "ride-order.get", "GET",
                "https://secret.internal/business-example-value", false, ""), base.ports(),
                base.configSchema(), new OperatorDefinition.Capabilities(
                "READ_EXTERNAL", "IDEMPOTENT", false, false, false), base.policy(),
                new OperatorDefinition.Lowering("runtime", "httpResource",
                        Map.of("bindingRef", "resource:ride-order.get")), List.of());
    }

    private static OperatorLibrary library(String id, List<OperatorDefinition> operators) {
        return new OperatorLibrary("bloge.visualOperatorLibrary.v1", id, id, "1.0.0", "owner", "ACTIVE",
                List.of(new OperatorLibrary.BuiltInFunction("rideRisk", id, "rideRisk",
                        "business-example-value", "business",
                        List.of(new OperatorLibrary.Signature("rideRisk(secret)", "unsafe",
                                List.of(new OperatorLibrary.Parameter("secret", "string", null,
                                        false, false, "unsafe")),
                                new OperatorLibrary.ReturnValue("number", null, "unsafe"))),
                        List.of("rideRisk(\"business-example-value\")"))), operators);
    }

    private static IntegrationRequestContext identity(String projectId) {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", projectId, "test", "sg", "WORKLOAD", "codex-1",
                "", "AGENT_TDD_READ", "corr-1");
    }

    private record FixedCatalog(List<OperatorDefinition> operators) implements VisualOperatorCatalog {
        @Override
        public List<OperatorDefinition> list(OperatorCatalogQuery query) {
            return operators.stream().filter(operator -> operator.policy().allows(
                    query.tenantId(), query.namespace(), query.environment())).toList();
        }

        @Override
        public List<OperatorLibrary.BuiltInFunction> builtInFunctions(OperatorCatalogQuery query) {
            return query.operatorLibraryIds().isEmpty() ? List.of() : List.of(
                    new OperatorLibrary.BuiltInFunction("rideRisk", "ride-policy", "rideRisk",
                            "business-example-value", "business",
                            List.of(new OperatorLibrary.Signature("rideRisk(secret)", "unsafe",
                                    List.of(new OperatorLibrary.Parameter("secret", "string", null,
                                            false, false, "unsafe")),
                                    new OperatorLibrary.ReturnValue("number", null, "unsafe"))),
                            List.of("business-example-value")));
        }

        @Override
        public Optional<OperatorDefinition> find(String operatorRef) {
            return operators.stream().filter(operator -> operator.operatorRef().equals(operatorRef)).findFirst();
        }
    }

    private static class FixedLibraries implements OperatorLibraryRegistry {
        private final List<OperatorLibrary> values;

        private FixedLibraries(List<OperatorLibrary> values) {
            this.values = List.copyOf(values);
        }

        @Override public Collection<OperatorLibrary> all() { return values; }
        @Override public Optional<OperatorLibrary> find(String libraryId) {
            return values.stream().filter(library -> library.libraryId().equals(libraryId)).findFirst();
        }
        @Override public List<com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRevision> revisions(
                String libraryId) { return List.of(); }
        @Override public Optional<com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRevision> findRevision(
                String libraryId, long revision) { return Optional.empty(); }
        @Override public OperatorLibrary upsert(OperatorLibrary library,
                                                com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRevision.RevisionMetadata metadata) {
            throw new UnsupportedOperationException();
        }
        @Override public OperatorLibrary restore(
                com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRevision revision,
                com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRevision.RevisionMetadata metadata) {
            throw new UnsupportedOperationException();
        }
        @Override public void delete(String libraryId,
                                     com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRevision.RevisionMetadata metadata) {
            throw new UnsupportedOperationException();
        }
    }
}
