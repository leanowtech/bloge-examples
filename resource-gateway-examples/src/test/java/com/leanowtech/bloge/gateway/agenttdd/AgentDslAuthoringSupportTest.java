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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies the public, scoped and payload-free DSL authoring reference seam. */
class AgentDslAuthoringSupportTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void emptyLibraryRefsExposeOnlyBuiltInsAndNeverLeakRuntimeMaterial() {
        OperatorDefinition builtIn = operator("bloge:transform", "", OperatorDefinition.Policy.unrestricted());
        OperatorDefinition library = operator("ride:lookup", "ride-policy", OperatorDefinition.Policy.unrestricted());
        AgentDslAuthoringSupport support = support(List.of(builtIn, library), List.of());

        DslReferenceSnapshot reference = support.reference(
                new DslReferenceRequest(List.of(), List.of("graph", "bindings"),
                        List.of(), true), identity("project-a"));

        assertThat(reference.schemaVersion()).isEqualTo("rg.dslReference.v1");
        assertThat(reference.supportedRootKinds()).containsExactly("graph");
        assertThat(reference.operators()).extracting(DslReferenceSnapshot.OperatorContract::operatorRef)
                .containsExactly("bloge:transform");
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

    private record FixedLibraries(List<OperatorLibrary> values) implements OperatorLibraryRegistry {
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
