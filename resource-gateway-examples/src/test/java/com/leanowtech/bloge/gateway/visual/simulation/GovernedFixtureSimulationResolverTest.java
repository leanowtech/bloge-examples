package com.leanowtech.bloge.gateway.visual.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactSchemaRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.FixtureLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.FixtureSource;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.SourceKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.RetentionDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.RedactionDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.FixtureSubject;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.Receipt;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialResolver;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.FixtureAssetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredFixtureAsset;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.fixture.GraphNodeFixturePromotionService;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Exercises governed Fixture closure, current-node schema checks, and protected material access. */
class GovernedFixtureSimulationResolverTest {
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);
    private final EnterpriseScope scope = new EnterpriseScope("tenant", "org", "project", "test", "sg");
    private final IntegrationRequestContext identity = new IntegrationRequestContext(
            "tenant", "org", "project", "test", "sg", "USER", "author", "",
            "CORRECTNESS_FIXTURE_MATERIAL_READ", "corr");
    private FixtureAssetRepository fixtures;
    private FixtureMaterialResolver materials;
    private VisualOperatorCatalog catalog;
    private GovernedFixtureSimulationResolver resolver;
    private StoredFixtureAsset stored;
    private ExactAssetRef materialRef;
    private ExactSchemaRef schemaRef;

    @BeforeEach
    void setUp() {
        fixtures = mock(FixtureAssetRepository.class);
        materials = mock(FixtureMaterialResolver.class);
        catalog = mock(VisualOperatorCatalog.class);
        materialRef = new ExactAssetRef("FIXTURE_MATERIAL", "fixture", 1, FINGERPRINT);
        schemaRef = new ExactSchemaRef("applicant", 1, FINGERPRINT);
        var descriptor = mock(com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.class);
        when(descriptor.fixtureAssetId()).thenReturn("fixture");
        when(descriptor.revision()).thenReturn(1L);
        when(descriptor.lifecycle()).thenReturn(FixtureLifecycle.ACTIVE);
        when(descriptor.schemaRef()).thenReturn(schemaRef);
        when(descriptor.materialRef()).thenReturn(materialRef);
        stored = mock(StoredFixtureAsset.class);
        when(stored.descriptor()).thenReturn(descriptor);
        when(stored.exactRef()).thenReturn(materialRef);
        when(fixtures.findRevision(scope, "fixture", 1)).thenReturn(Optional.of(stored));
        Receipt receipt = receiptFor(schemaRef);
        var resolved = new FixtureMaterialResolver.ResolvedFixtureMaterial(
                materialRef, receipt, Map.of("score", 760));
        when(materials.resolve(eq(scope), eq(materialRef), any()))
                .thenReturn(resolved);
        resolver = new GovernedFixtureSimulationResolver(fixtures, materials);
    }

    @Test
    void resolvesActiveFixtureThroughMaterialBoundaryAndReturnsOnlyOutput() {
        NodeFixture result = resolver.resolve(scope,
                new GovernedFixtureRef("fixture", 1, FINGERPRINT), identity);

        assertThat(result.output()).isEqualTo(Map.of("score", 760));
        assertThat(result.expectedInput()).isNull();
        ArgumentCaptor<FixtureMaterialResolver.MaterialAccessContext> access =
                ArgumentCaptor.forClass(FixtureMaterialResolver.MaterialAccessContext.class);
        verify(materials).resolve(eq(scope), eq(materialRef), access.capture());
        assertThat(access.getValue().actorId()).isEqualTo("author");
        assertThat(access.getValue().purpose()).isEqualTo("CORRECTNESS_FIXTURE_MATERIAL_READ");
        assertThat(access.getValue().correlationId()).isEqualTo("corr");
    }

    @Test
    void rejectsInactiveRevisionOrSchemaBeforeReadingProtectedMaterial() {
        when(stored.descriptor().lifecycle()).thenReturn(FixtureLifecycle.DRAFT);
        assertThatThrownBy(() -> resolver.resolve(scope,
                new GovernedFixtureRef("fixture", 1, FINGERPRINT), identity))
                .isInstanceOf(GovernedFixtureSimulationResolver.GovernedFixtureResolutionException.class);
        verifyNoInteractions(materials);

        when(stored.descriptor().lifecycle()).thenReturn(FixtureLifecycle.ACTIVE);
        when(stored.descriptor().schemaRef()).thenReturn(new ExactSchemaRef("applicant", 1,
                "sha256:" + "b".repeat(64)));
        assertThatThrownBy(() -> resolver.resolve(scope,
                new GovernedFixtureRef("fixture", 1, FINGERPRINT), identity))
                .isInstanceOf(GovernedFixtureSimulationResolver.GovernedFixtureResolutionException.class);
        verifyNoInteractions(materials);
        assertThatThrownBy(() -> resolver.resolve(scope,
                new GovernedFixtureRef("fixture", 2, FINGERPRINT), identity))
                .isInstanceOf(GovernedFixtureSimulationResolver.GovernedFixtureResolutionException.class);
    }

    @Test
    void rejectsCurrentNodeSchemaDriftOpaqueMultipleOutputsAndInvalidPayload() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        OperatorDefinition operator = operator(1, SchemaEnvelope.object(
                Map.of("score", Map.of("type", "integer")), List.of("score")));
        when(catalog.find("resource:applicant")).thenReturn(Optional.of(operator));
        GovernedFixtureSimulationResolver current = new GovernedFixtureSimulationResolver(
                fixtures, materials, catalog, mapper);
        GraphDraft draft = draft();
        String fingerprint = GraphNodeFixturePromotionService.exactOutputSchemaRef(operator, mapper).fingerprint();
        ExactSchemaRef currentSchemaRef = new ExactSchemaRef("applicant", 1, fingerprint);
        when(stored.descriptor().schemaRef()).thenReturn(currentSchemaRef);
        when(materials.resolve(eq(scope), eq(materialRef), any())).thenReturn(
                new FixtureMaterialResolver.ResolvedFixtureMaterial(materialRef,
                        receiptFor(currentSchemaRef), Map.of("score", 760)));
        assertThat(current.resolve(scope, new GovernedFixtureRef("fixture", 1, fingerprint),
                identity, draft, "node").output()).isEqualTo(Map.of("score", 760));

        assertThatThrownBy(() -> current.resolve(scope,
                new GovernedFixtureRef("fixture", 1, FINGERPRINT), identity, draft, "node"))
                .isInstanceOf(GovernedFixtureSimulationResolver.GovernedFixtureResolutionException.class);
        when(catalog.find("resource:applicant")).thenReturn(Optional.of(operator(2, operator.ports().outputs().getFirst().schema())));
        assertThatThrownBy(() -> current.resolve(scope,
                new GovernedFixtureRef("fixture", 1, fingerprint), identity, draft, "node"))
                .isInstanceOf(GovernedFixtureSimulationResolver.GovernedFixtureResolutionException.class);
        when(catalog.find("resource:applicant")).thenReturn(Optional.of(operator(1,
                SchemaEnvelope.opaque())));
        assertThatThrownBy(() -> current.resolve(scope,
                new GovernedFixtureRef("fixture", 1, fingerprint), identity, draft, "node"))
                .isInstanceOf(GovernedFixtureSimulationResolver.GovernedFixtureResolutionException.class);
        when(materials.resolve(eq(scope), eq(materialRef), any())).thenReturn(
                new FixtureMaterialResolver.ResolvedFixtureMaterial(materialRef,
                        receiptFor(new ExactSchemaRef("applicant", 1, fingerprint)),
                        Map.of("score", "not-an-integer")));
        assertThatThrownBy(() -> current.resolve(scope,
                new GovernedFixtureRef("fixture", 1, fingerprint), identity, draft, "node"))
                .isInstanceOf(GovernedFixtureSimulationResolver.GovernedFixtureResolutionException.class);
    }

    @Test
    void recordsContentAddressedReuseIdempotentlyForSuccessfulConsumers() {
        GovernedFixtureSimulationResolver usageResolver =
                new GovernedFixtureSimulationResolver(fixtures, materials);
        GovernedFixtureRef ref = new GovernedFixtureRef("fixture", 1, FINGERPRINT);

        usageResolver.recordReuse(scope, draft(), List.of(ref, ref));
        usageResolver.recordReuse(scope, draft(), List.of(ref));

        ArgumentCaptor<ExactAssetRef> consumer = ArgumentCaptor.forClass(ExactAssetRef.class);
        verify(fixtures, times(2)).replaceUsageForConsumer(
                eq(scope), consumer.capture(), eq(List.of(materialRef)));
        assertThat(consumer.getAllValues()).hasSize(2)
                .allSatisfy(value -> assertThat(value.kind()).isEqualTo("GRAPH_SIMULATION"));
        assertThat(consumer.getAllValues().get(0)).isEqualTo(consumer.getAllValues().get(1));
    }

    private static GraphDraft draft() {
        return new GraphDraft("", "draft", 1, "graph", "tenant", "project", "test",
                GraphDraft.STATUS_DRAFT, SchemaEnvelope.opaque(), SchemaEnvelope.opaque(),
                List.of(new GraphDraft.DraftNode(
                        "node", "resource:applicant", "Applicant", Map.of(), Map.of(), null)),
                List.of(), Map.of(), Map.of(), new GraphDraft.OutputSelection("node", ""), Map.of(), Map.of(),
                GraphDraft.RevisionMetadata.empty());
    }

    private static Receipt receiptFor(ExactSchemaRef schema) {
        return new Receipt("", "fixture", new ExactAssetRef(
                "FIXTURE_MATERIAL", "fixture", 1, FINGERPRINT), FINGERPRINT,
                new FixtureSource(SourceKind.SAMPLE, null), FixtureSubject.GRAPH,
                new com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef(
                        com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind.GRAPH,
                        "draft", 1, FINGERPRINT), schema, "PUBLIC",
                new RetentionDescriptor("test", 3, java.time.Instant.now().plusSeconds(86400)),
                new RedactionDescriptor("test", List.of(), false), List.of(), true, false);
    }

    private static OperatorDefinition operator(int outputs, SchemaEnvelope schema) {
        List<OperatorDefinition.Port> ports = java.util.stream.IntStream.range(0, outputs)
                .mapToObj(i -> new OperatorDefinition.Port("payload" + i, schema, true, ""))
                .toList();
        return new OperatorDefinition("", "resource:applicant", "1", "",
                new OperatorDefinition.Display("Applicant", "", List.of()),
                new OperatorDefinition.Source("resource", "applicant", "GET", "/", true),
                new OperatorDefinition.Ports(List.of(), ports), SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(), null,
                new OperatorDefinition.Lowering("native", "", Map.of()), List.of());
    }
}
