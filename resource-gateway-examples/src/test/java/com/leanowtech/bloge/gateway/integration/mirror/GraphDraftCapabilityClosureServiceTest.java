package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.exception.ResourceNotFoundException;
import com.leanowtech.bloge.gateway.resource.ParameterMapping;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.resource.ResponseProtocol;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GraphDraftCapabilityClosureServiceTest {
    private static final Instant CREATED_AT = Instant.parse("2026-07-22T08:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final VisualOperatorCatalog catalog = mock(VisualOperatorCatalog.class);
    private final ResourceRegistry resources = mock(ResourceRegistry.class);
    private GraphDraftCapabilityClosureService service;

    @BeforeEach
    void setUp() {
        service = new GraphDraftCapabilityClosureService(catalog, resources,
                new CapabilityProjectionService(mapper), mapper);
    }

    @Test
    void pinsPortableDraftOperatorsAndProducesStableCompleteClosure() {
        OperatorDefinition resource = resourceOperator("resource:customers.get", "customers.get");
        OperatorDefinition transform = pureOperator("bloge:transform", Map.of("assignments", "v1"));
        when(catalog.findAll(any())).thenReturn(Map.of(
                resource.operatorRef(), resource,
                transform.operatorRef(), transform));
        when(resources.resolve("customers.get")).thenReturn(resource("customers.get"));
        GraphDraft portable = portableDraft(List.of(
                node("load", resource.operatorRef()), node("map", transform.operatorRef())), Map.of());

        CapabilityClosure first = service.project(portable, context());
        CapabilityClosure second = service.project(portable, context());

        CapabilityClosureIntegrity.verify(mapper, first);
        assertThat(first.fingerprint()).isEqualTo(second.fingerprint());
        assertThat(first.snapshots()).hasSize(2);
        assertThat(first.snapshots()).extracting(CapabilitySnapshot::capabilityId)
                .containsExactly("graph:customerView", "resource:customers.get");
        CapabilitySnapshot root = first.snapshots().stream()
                .filter(snapshot -> snapshot.kind() == CapabilitySnapshot.Kind.COMPOSED)
                .findFirst().orElseThrow();
        assertThat(root.dependencies()).singleElement().satisfies(dependency -> {
            assertThat(dependency.nodeId()).isEqualTo("load");
            assertThat(dependency.capabilityRef().id()).isEqualTo("resource:customers.get");
        });
        assertThat(root.contract().effect().mode()).isEqualTo(EffectContract.Mode.READ_ONLY);
    }

    @Test
    void preservesSavedOperatorSnapshotWithoutConsultingCurrentCatalog() {
        OperatorDefinition saved = resourceOperator("resource:customers.get", "customers.get");
        when(catalog.findAll(any())).thenReturn(Map.of());
        when(resources.resolve("customers.get")).thenReturn(resource("customers.get"));
        GraphDraft draft = portableDraft(List.of(node("load", saved.operatorRef())), Map.of())
                .withOperatorSnapshotState(Map.of("load", saved.fingerprint()), Map.of("load", saved));

        CapabilityClosure closure = service.project(draft, context());

        assertThat(closure.snapshots()).hasSize(2);
        CapabilityClosureIntegrity.verify(mapper, closure);
    }

    @Test
    void rejectsStalePinnedFingerprintBeforeEmittingClosure() {
        OperatorDefinition operator = resourceOperator("resource:customers.get", "customers.get");
        when(catalog.findAll(any())).thenReturn(Map.of(operator.operatorRef(), operator));
        GraphDraft draft = portableDraft(List.of(node("load", operator.operatorRef())), Map.of())
                .withOperatorFingerprints(Map.of("load", "sha256:" + "0".repeat(64)));

        assertProjectionCode(() -> service.project(draft, context()),
                "RG.MIRROR.OPERATOR_FINGERPRINT_MISMATCH");
    }

    @Test
    void rejectsMissingOperatorAndMissingResourceDescriptorWithStableCodes() {
        when(catalog.findAll(any())).thenReturn(Map.of());
        GraphDraft missingOperator = portableDraft(List.of(node("load", "resource:customers.get")), Map.of());
        assertProjectionCode(() -> service.project(missingOperator, context()),
                "RG.MIRROR.OPERATOR_SNAPSHOT_MISSING");

        OperatorDefinition resource = resourceOperator("resource:customers.get", "customers.get");
        when(catalog.findAll(any())).thenReturn(Map.of(resource.operatorRef(), resource));
        when(resources.resolve("customers.get")).thenThrow(new ResourceNotFoundException("customers.get"));
        assertProjectionCode(() -> service.project(missingOperator, context()),
                "RG.MIRROR.RESOURCE_DESCRIPTOR_MISSING");
    }

    @Test
    void rejectsNestedGraphUntilAnExactChildClosureIsSupplied() {
        OperatorDefinition nested = operator("publication:customerSummary",
                new OperatorDefinition.Source("composed-graph", "published-customer-summary", "", "", true),
                OperatorDefinition.Capabilities.pure(), Map.of());
        when(catalog.findAll(any())).thenReturn(Map.of(nested.operatorRef(), nested));
        GraphDraft draft = portableDraft(List.of(node("nested", nested.operatorRef())), Map.of());

        assertProjectionCode(() -> service.project(draft, context()),
                "RG.MIRROR.NESTED_CAPABILITY_CLOSURE_REQUIRED");
    }

    @Test
    void rejectsDuplicateNodeIdentityBeforeCatalogResolution() {
        GraphDraft draft = portableDraft(List.of(
                node("same", "resource:first"), node("same", "resource:second")), Map.of());

        assertProjectionCode(() -> service.project(draft, context()),
                "RG.MIRROR.DUPLICATE_GRAPH_NODE");
    }

    private static void assertProjectionCode(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(CapabilityProjectionException.Failure.class,
                        failure -> assertThat(failure.problem().code()).isEqualTo(code));
    }

    private static GraphDraft portableDraft(List<GraphDraft.DraftNode> nodes,
                                            Map<String, String> fingerprints) {
        return new GraphDraft("", "", 0, "customerView", "demo-tenant", "local", "local", "DRAFT",
                objectSchema("customerId"), objectSchema("customer"), nodes, List.of(), Map.of(), Map.of(),
                new GraphDraft.OutputSelection(nodes.getLast().id(), ""), fingerprints, Map.of(),
                GraphDraft.RevisionMetadata.empty());
    }

    private static GraphDraft.DraftNode node(String id, String operatorRef) {
        return new GraphDraft.DraftNode(id, operatorRef, id, Map.of(), Map.of(), new GraphDraft.Position(0, 0));
    }

    private static ResourceDescriptor resource(String id) {
        return new ResourceDescriptor(id, "https://api.example.test/" + id, "GET",
                Map.of("Accept", "application/json"), null, Duration.ofSeconds(3), ParameterMapping.empty(),
                new ResponseProtocol.HttpStatus(), "data");
    }

    private static OperatorDefinition resourceOperator(String operatorRef, String resourceId) {
        return operator(operatorRef,
                new OperatorDefinition.Source("resource-descriptor", resourceId, "GET",
                        "https://api.example.test/" + resourceId, true),
                new OperatorDefinition.Capabilities("READ_EXTERNAL", "IDEMPOTENT",
                        false, false, false), Map.of());
    }

    private static OperatorDefinition pureOperator(String operatorRef, Map<String, Object> parameters) {
        return operator(operatorRef, OperatorDefinition.Source.builtIn("bloge-operator"),
                OperatorDefinition.Capabilities.pure(), parameters);
    }

    private static OperatorDefinition operator(String operatorRef,
                                               OperatorDefinition.Source source,
                                               OperatorDefinition.Capabilities capabilities,
                                               Map<String, Object> parameters) {
        OperatorDefinition.Ports ports = new OperatorDefinition.Ports(
                List.of(new OperatorDefinition.Port("inputs", objectSchema("id"), true, "")),
                List.of(new OperatorDefinition.Port("output", objectSchema("value"), true, "")));
        return new OperatorDefinition("", operatorRef, "1.0.0", "",
                new OperatorDefinition.Display(operatorRef, "", List.of()), source, ports,
                SchemaEnvelope.opaque(), capabilities, OperatorDefinition.Policy.unrestricted(),
                new OperatorDefinition.Lowering("native", operatorRef, parameters), List.of());
    }

    private static SchemaEnvelope objectSchema(String property) {
        return SchemaEnvelope.object(Map.of(property, Map.of("type", "string")), List.of(property));
    }

    private static CapabilityProjectionContext context() {
        return new CapabilityProjectionContext(1, "tenant-a", "org-a", "support-platform",
                "test", "sg", "CAPABILITY_PROJECTION",
                new CapabilitySnapshot.Ownership("owner-a", "support-platform", "pager-a"),
                CapabilitySnapshot.Lifecycle.DRAFT, CapabilityContract.DataClassification.CONFIDENTIAL,
                List.of("sg"), false, "", null, null, CREATED_AT);
    }
}
