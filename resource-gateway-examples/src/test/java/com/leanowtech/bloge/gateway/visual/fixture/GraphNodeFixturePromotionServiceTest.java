package com.leanowtech.bloge.gateway.visual.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.Receipt;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.WriteRequest;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureCatalogCommandException;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureCatalogService;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredFixtureAsset;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Proves server-owned derivation and typed HTTP semantics for graph-node promotion. */
class GraphNodeFixturePromotionServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");

    private GraphDraftRepository drafts;
    private VisualOperatorCatalog operators;
    private FixtureCatalogService fixtureCatalog;
    private List<WriteRequest> materialWrites;
    private ObjectMapper mapper;
    private GraphNodeFixturePromotionService service;
    private IntegrationRequestContext identity;

    @BeforeEach
    void setUp() {
        drafts = mock(GraphDraftRepository.class);
        operators = mock(VisualOperatorCatalog.class);
        fixtureCatalog = mock(FixtureCatalogService.class);
        materialWrites = new java.util.ArrayList<>();
        mapper = new ObjectMapper().findAndRegisterModules();
        service = new GraphNodeFixturePromotionService(
                drafts,
                operators,
                fixtureCatalog,
                (request, requestIdentity) -> {
                    materialWrites.add(request);
                    return receipt(request);
                },
                mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
        identity = new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg", "USER", "author-1",
                "", FixtureMaterialWriterPurpose.VALUE, "correlation-1",
                Set.of(), "RESTRICTED", "");
    }

    @Test
    void derivesGovernedDraftFromExactGraphCapture() {
        when(drafts.find("draft-1")).thenReturn(Optional.of(draft(
                Map.of("node_1", new GraphDraft.NodeFixture(Map.of("score", 760))))));
        when(operators.find("resource:applicant")).thenReturn(Optional.of(resourceOperator()));
        var descriptor = new java.util.concurrent.atomic.AtomicReference<FixtureAssetDescriptor>();
        when(fixtureCatalog.saveDraft(eq(0L), any(), any())).thenAnswer(invocation -> {
            FixtureAssetDescriptor persisted = ((FixtureAssetDescriptor) invocation.getArgument(1))
                    .persistedAs(1, ((FixtureAssetDescriptor) invocation.getArgument(1)).metadata());
            descriptor.set(persisted);
            return StoredFixtureAsset.verified(mapper, persisted);
        });

        var result = service.promote(
                "draft-1", "node_1", request("applicant-fixture"), identity);

        assertThat(result.fixtureAssetId()).isEqualTo("applicant-fixture");
        assertThat(result.revision()).isEqualTo(1);
        assertThat(result.lifecycle()).isEqualTo("DRAFT");
        assertThat(result.provenance()).isEqualTo("governed");
        assertThat(materialWrites).hasSize(1);
        WriteRequest write = materialWrites.getFirst();
        assertThat(write.subject()).isEqualTo(com.leanowtech.bloge.gateway.testing.correctness.domain
                .FixtureMaterialProtocolV2.FixtureSubject.GRAPH);
        assertThat(write.source().kind()).isEqualTo(FixtureAssetDescriptor.SourceKind.SCENARIO);
        assertThat(write.source().sourceRef().kind()).isEqualTo("RESOURCE");
        assertThat(write.source().sourceRef().id()).isEqualTo("applicant");
        assertThat(write.classification()).isEqualTo("RESTRICTED");
        assertThat(write.redaction().redactedPaths()).containsExactly("$.phone");
        assertThat(write.payload()).isEqualTo(Map.of("score", 760));
        assertThat(descriptor.get().scope()).isEqualTo(new com.leanowtech.bloge.gateway.testing
                .correctness.domain.CorrectnessProtocol.EnterpriseScope(
                        "tenant-a", "org-a", "project-a", "test", "sg"));
        assertThat(descriptor.get().owner().id()).isEqualTo("author-1");
        assertThat(descriptor.get().quality().schemaValid()).isTrue();
        assertThat(descriptor.get().redaction().reviewed()).isFalse();
        assertThat(descriptor.get().retention().expiresAt()).isEqualTo(NOW.plusSeconds(3 * 86_400));
        assertThat(descriptor.get().variantKey()).isEqualTo("node_1");
    }

    @Test
    void returnsNotFoundAndUnprocessableWithoutMaterialWrite() {
        when(drafts.find("missing")).thenReturn(Optional.empty());
        when(drafts.find("draft-1")).thenReturn(Optional.of(draft(Map.of())));

        assertThatThrownBy(() -> service.promote(
                "missing", "node_1", request("new-id"), identity))
                .isInstanceOfSatisfying(GraphNodeFixturePromotionException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(404);
                    assertThat(failure.code()).isEqualTo("RG.VISUAL.PROMOTION.DRAFT_NOT_FOUND");
                });
        assertThatThrownBy(() -> service.promote(
                "draft-1", "node_1", request("new-id"), identity))
                .isInstanceOfSatisfying(GraphNodeFixturePromotionException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(422);
                    assertThat(failure.code()).isEqualTo("RG.VISUAL.PROMOTION.OUTPUT_MISSING");
                });
        assertThat(materialWrites).isEmpty();
    }

    @Test
    void rejectsUnsupportedContractAndMapsCatalogConflictToConflict() {
        when(drafts.find("draft-1")).thenReturn(Optional.of(draft(
                Map.of("node_1", new GraphDraft.NodeFixture(Map.of("score", 760))))));
        when(operators.find("resource:applicant")).thenReturn(Optional.of(resourceOperator()));
        when(fixtureCatalog.saveDraft(eq(0L), any(), any()))
                .thenThrow(new FixtureCatalogCommandException(
                        "RG.CORRECTNESS.REVISION_CONFLICT", "Fixture id already exists"));

        assertThatThrownBy(() -> service.promote(
                "draft-1", "node_1",
                new GraphNodeFixturePromotionRequest("wrong-version", "new-id", "INTERNAL", 3,
                        List.of(), true), identity))
                .isInstanceOfSatisfying(GraphNodeFixturePromotionException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(422);
                    assertThat(failure.code()).isEqualTo("RG.VISUAL.PROMOTION.REQUEST_INVALID");
                });
        assertThatThrownBy(() -> service.promote(
                "draft-1", "node_1", request("existing-id"), identity))
                .isInstanceOfSatisfying(GraphNodeFixturePromotionException.class, failure ->
                        assertThat(failure.status()).isEqualTo(409));
    }

    private GraphNodeFixturePromotionRequest request(String fixtureId) {
        return new GraphNodeFixturePromotionRequest(
                GraphNodeFixturePromotionRequest.SCHEMA_VERSION, fixtureId, "restricted", 3,
                List.of("$.phone"), true);
    }

    private static GraphDraft draft(Map<String, GraphDraft.NodeFixture> fixtures) {
        return new GraphDraft(
                null, "draft-1", 1, "Loan tool", "tenant-a", "project-a", "test",
                GraphDraft.STATUS_DRAFT, SchemaEnvelope.opaque(),
                List.of(new GraphDraft.DraftNode(
                        "node_1", "resource:applicant", "Applicant", Map.of(), Map.of(), null)),
                List.of(), Map.of(), fixtures, null, Map.of(), Map.of(),
                GraphDraft.RevisionMetadata.empty());
    }

    private static OperatorDefinition resourceOperator() {
        SchemaEnvelope output = SchemaEnvelope.object(
                Map.of("score", Map.of("type", "integer")), List.of("score"));
        return new OperatorDefinition(
                "", "resource:applicant", "1.0.0", "",
                new OperatorDefinition.Display("Applicant profile", "", List.of()),
                new OperatorDefinition.Source(
                        "resource-descriptor", "applicant", "GET", "/applicants/{id}", true),
                new OperatorDefinition.Ports(List.of(), List.of(
                        new OperatorDefinition.Port("payload", output, true, ""))),
                SchemaEnvelope.object(Map.of(), List.of()),
                OperatorDefinition.Capabilities.pure(),
                null,
                new OperatorDefinition.Lowering("resource-descriptor", "httpResource", Map.of()),
                List.of());
    }

    private static Receipt receipt(WriteRequest request) {
        String fingerprint = CorrectnessProtocolFingerprint.derivedFingerprint(
                new ObjectMapper().findAndRegisterModules(), request.payload());
        return new Receipt(
                "", request.fixtureAssetId(),
                new com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef(
                        "FIXTURE_MATERIAL", request.fixtureAssetId(), 1, fingerprint),
                fingerprint, request.source(), request.subject(), request.target(), request.schemaRef(),
                request.classification(), request.retention(), request.redaction(),
                List.of(request.source().sourceRef()), true, false);
    }

    /** Test-only semantic constant for the required material write purpose. */
    private static final class FixtureMaterialWriterPurpose {
        private FixtureMaterialWriterPurpose() { }
        private static final String VALUE = IntegrationOperation
                .CORRECTNESS_FIXTURE_MATERIAL_WRITE.acceptedPurposes().iterator().next();
    }
}
