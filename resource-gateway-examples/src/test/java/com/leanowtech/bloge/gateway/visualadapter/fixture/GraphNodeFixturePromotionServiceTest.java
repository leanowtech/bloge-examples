package com.leanowtech.bloge.gateway.visualadapter.fixture;

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
import com.leanowtech.bloge.gateway.visual.simulation.InMemoryVisualSimulationCaptureEvidenceRepository;
import com.leanowtech.bloge.gateway.visual.simulation.NodeFixture;
import com.leanowtech.bloge.gateway.visual.simulation.VisualGraphSimulationRequest;
import com.leanowtech.bloge.gateway.visual.simulation.VisualGraphSimulationResponse;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
        assertThat(write.source().kind()).isEqualTo(FixtureAssetDescriptor.SourceKind.SAMPLE);
        assertThat(write.source().sourceRef().kind()).isEqualTo("RESOURCE");
        assertThat(write.source().sourceRef().id()).isEqualTo("applicant");
        assertThat(write.classification()).isEqualTo("RESTRICTED");
        assertThat(write.redaction().redactedPaths()).containsExactly("/score");
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
    void derivesScenarioSourceOnlyFromMatchingServerSimulationCapture() {
        GraphDraft unpinnedDraft = draft(Map.of());
        GraphDraft pinnedDraft = draft(Map.of("node_1", new GraphDraft.NodeFixture(
                Map.of("score", 760))));
        when(drafts.find("draft-1")).thenReturn(Optional.of(pinnedDraft));
        OperatorDefinition operator = resourceOperator();
        when(operators.find("resource:applicant")).thenReturn(Optional.of(operator));
        when(fixtureCatalog.saveDraft(eq(0L), any(), any())).thenAnswer(invocation -> {
            FixtureAssetDescriptor candidate = invocation.getArgument(1);
            return StoredFixtureAsset.verified(mapper, candidate.persistedAs(1, candidate.metadata()));
        });
        InMemoryVisualSimulationCaptureEvidenceRepository captures =
                new InMemoryVisualSimulationCaptureEvidenceRepository(
                        mapper, Clock.fixed(NOW, ZoneOffset.UTC), java.time.Duration.ofMinutes(10), 8);
        VisualGraphSimulationResponse simulation = new VisualGraphSimulationResponse(
                true, true, true, "Loan tool", "node_1", Map.of("score", 760),
                Map.of("node_1", Map.of("score", 760)), Map.of("node_1", "COMPLETED"),
                1, Map.of("node_1", 1L), List.of("node_1"), List.of(), true,
                List.of(), List.of(), "graph Loan_tool {}", Map.of("node_1", "OUTPUT_LEVEL"));
        captures.recordSuccessfulSimulation(
                new VisualGraphSimulationRequest(unpinnedDraft, Map.of(), "node_1", Map.of()),
                simulation,
                operators);
        GraphNodeFixturePromotionService lineageService = new GraphNodeFixturePromotionService(
                drafts, operators, fixtureCatalog, (request, requestIdentity) -> {
                    materialWrites.add(request);
                    return receipt(request);
                }, mapper, Clock.fixed(NOW, ZoneOffset.UTC), captures);

        lineageService.promote("draft-1", "node_1", request("scenario-fixture"), identity);

        assertThat(materialWrites).singleElement()
                .extracting(WriteRequest::source)
                .extracting(FixtureAssetDescriptor.FixtureSource::kind)
                .isEqualTo(FixtureAssetDescriptor.SourceKind.SCENARIO);
    }

    @Test
    void fallsBackToSampleWhenServerCaptureDoesNotMatchPinnedOutput() {
        GraphDraft unpinnedDraft = draft(Map.of());
        GraphDraft pinnedDraft = draft(Map.of("node_1", new GraphDraft.NodeFixture(
                Map.of("score", 761))));
        when(drafts.find("draft-1")).thenReturn(Optional.of(pinnedDraft));
        OperatorDefinition operator = resourceOperator();
        when(operators.find("resource:applicant")).thenReturn(Optional.of(operator));
        when(fixtureCatalog.saveDraft(eq(0L), any(), any())).thenAnswer(invocation -> {
            FixtureAssetDescriptor candidate = invocation.getArgument(1);
            return StoredFixtureAsset.verified(mapper, candidate.persistedAs(1, candidate.metadata()));
        });
        InMemoryVisualSimulationCaptureEvidenceRepository captures =
                new InMemoryVisualSimulationCaptureEvidenceRepository(
                        mapper, Clock.fixed(NOW, ZoneOffset.UTC), java.time.Duration.ofMinutes(10), 8);
        Object simulatedOutput = Map.of("score", 760);
        captures.recordSuccessfulSimulation(
                new VisualGraphSimulationRequest(unpinnedDraft, Map.of(), "node_1", Map.of()),
                new VisualGraphSimulationResponse(
                        true, true, true, "Loan tool", "node_1", simulatedOutput,
                        Map.of("node_1", simulatedOutput), Map.of("node_1", "COMPLETED"), 1,
                        Map.of("node_1", 1L), List.of("node_1"), List.of(), true,
                        List.of(), List.of(), "graph Loan_tool {}", Map.of()),
                operators);
        GraphNodeFixturePromotionService lineageService = new GraphNodeFixturePromotionService(
                drafts, operators, fixtureCatalog, (request, requestIdentity) -> {
                    materialWrites.add(request);
                    return receipt(request);
                }, mapper, Clock.fixed(NOW, ZoneOffset.UTC), captures);

        lineageService.promote("draft-1", "node_1", request("sample-fallback"), identity);

        assertThat(materialWrites).singleElement()
                .extracting(WriteRequest::source)
                .extracting(FixtureAssetDescriptor.FixtureSource::kind)
                .isEqualTo(FixtureAssetDescriptor.SourceKind.SAMPLE);
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
                        List.of()), identity))
                .isInstanceOfSatisfying(GraphNodeFixturePromotionException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(400);
                    assertThat(failure.code()).isEqualTo("RG.VISUAL.PROMOTION.REQUEST_INVALID");
                });
        assertThatThrownBy(() -> service.promote(
                "draft-1", "node_1", request("existing-id"), identity))
                .isInstanceOfSatisfying(GraphNodeFixturePromotionException.class, failure ->
                        assertThat(failure.status()).isEqualTo(409));
    }

    @Test
    void mapsEveryClientControlledRequestViolationToBadRequest() {
        List<GraphNodeFixturePromotionRequest> invalidRequests = List.of(
                new GraphNodeFixturePromotionRequest("wrong-version", "fixture", "INTERNAL", 3, List.of()),
                new GraphNodeFixturePromotionRequest(GraphNodeFixturePromotionRequest.SCHEMA_VERSION,
                        "fixture", null, 3, List.of()),
                new GraphNodeFixturePromotionRequest(GraphNodeFixturePromotionRequest.SCHEMA_VERSION,
                        "fixture", "INTERNAL", 0, List.of()),
                new GraphNodeFixturePromotionRequest(GraphNodeFixturePromotionRequest.SCHEMA_VERSION,
                        "fixture", "INTERNAL", 3, List.of(" ")),
                new GraphNodeFixturePromotionRequest(GraphNodeFixturePromotionRequest.SCHEMA_VERSION,
                        "fixture", "INTERNAL", 3, List.of("$.score")));

        for (GraphNodeFixturePromotionRequest invalidRequest : invalidRequests) {
            assertThatThrownBy(() -> service.promote("draft-1", "node_1", invalidRequest, identity))
                    .isInstanceOfSatisfying(GraphNodeFixturePromotionException.class, failure -> {
                        assertThat(failure.status()).isEqualTo(400);
                        assertThat(failure.code()).isEqualTo("RG.VISUAL.PROMOTION.REQUEST_INVALID");
                    });
        }
        assertThat(materialWrites).isEmpty();
    }

    @Test
    void mapsMissingRequestBodyToBadRequest() {
        assertThatThrownBy(() -> service.promote("draft-1", "node_1", null, identity))
                .isInstanceOfSatisfying(GraphNodeFixturePromotionException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(400);
                    assertThat(failure.code()).isEqualTo("RG.VISUAL.PROMOTION.REQUEST_INVALID");
                });
    }

    @Test
    void rejectsUnknownNodeAndAmbiguousOutputSchemaBeforeWritingMaterial() {
        when(drafts.find("draft-1")).thenReturn(Optional.of(draft(
                Map.of("node_1", new GraphDraft.NodeFixture(Map.of("score", 760))))));
        when(operators.find("resource:applicant")).thenReturn(Optional.of(resourceOperatorWithOutputs(2)));

        assertThatThrownBy(() -> service.promote("draft-1", "missing", request("fixture"), identity))
                .isInstanceOfSatisfying(GraphNodeFixturePromotionException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(404);
                    assertThat(failure.code()).isEqualTo("RG.VISUAL.PROMOTION.NODE_NOT_FOUND");
                });
        assertThatThrownBy(() -> service.promote("draft-1", "node_1", request("fixture"), identity))
                .isInstanceOfSatisfying(GraphNodeFixturePromotionException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(422);
                    assertThat(failure.code()).isEqualTo("RG.VISUAL.PROMOTION.OUTPUT_SCHEMA_NON_UNIQUE");
                });
        assertThat(materialWrites).isEmpty();
    }

    @Test
    void promotesOnlyTheExplicitOutputPortFromAMultiOutputCapture() {
        when(drafts.find("draft-1")).thenReturn(Optional.of(draft(Map.of(
                "node_1", new GraphDraft.NodeFixture(Map.of(
                        "payload-0", Map.of("score", 760),
                        "payload-1", Map.of("score", 810)))))));
        when(operators.find("resource:applicant")).thenReturn(Optional.of(resourceOperatorWithOutputs(2)));
        when(fixtureCatalog.saveDraft(eq(0L), any(), any())).thenAnswer(invocation -> {
            FixtureAssetDescriptor candidate = invocation.getArgument(1);
            return StoredFixtureAsset.verified(mapper, candidate.persistedAs(1, candidate.metadata()));
        });

        service.promote("draft-1", "node_1", "payload-1", request("selected-port"), identity);

        assertThat(materialWrites).singleElement().satisfies(write -> {
            assertThat(write.payload()).isEqualTo(Map.of("score", 810));
            assertThat(write.schemaRef().id()).endsWith(":payload-1");
        });
    }

    @Test
    void rejectsUnknownOrMissingMultiOutputPortBeforeMaterialWrite() {
        when(drafts.find("draft-1")).thenReturn(Optional.of(draft(Map.of(
                "node_1", new GraphDraft.NodeFixture(Map.of("payload-0", Map.of("score", 760)))))));
        when(operators.find("resource:applicant")).thenReturn(Optional.of(resourceOperatorWithOutputs(2)));

        assertThatThrownBy(() -> service.promote(
                "draft-1", "node_1", "unknown", request("fixture"), identity))
                .isInstanceOfSatisfying(GraphNodeFixturePromotionException.class, failure ->
                        assertThat(failure.code()).isEqualTo("RG.VISUAL.PROMOTION.OUTPUT_PORT_NOT_FOUND"));
        assertThatThrownBy(() -> service.promote(
                "draft-1", "node_1", "payload-1", request("fixture"), identity))
                .isInstanceOfSatisfying(GraphNodeFixturePromotionException.class, failure ->
                        assertThat(failure.code()).isEqualTo("RG.VISUAL.PROMOTION.OUTPUT_PORT_VALUE_MISSING"));
        assertThat(materialWrites).isEmpty();
    }

    @Test
    void rejectsOpaqueOutputSchemaAndIncompleteIdentity() {
        when(drafts.find("draft-1")).thenReturn(Optional.of(draft(
                Map.of("node_1", new GraphDraft.NodeFixture(Map.of("score", 760))))));
        when(operators.find("resource:applicant")).thenReturn(Optional.of(resourceOperatorWithSchema(
                SchemaEnvelope.opaque(), 1)));

        assertThatThrownBy(() -> service.promote("draft-1", "node_1", request("fixture"), identity))
                .isInstanceOfSatisfying(GraphNodeFixturePromotionException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(422);
                    assertThat(failure.code()).isEqualTo("RG.VISUAL.PROMOTION.OUTPUT_SCHEMA_OPAQUE");
                });
        IntegrationRequestContext incomplete = new IntegrationRequestContext(
                "tenant-a", "", "project-a", "test", "sg", "USER", "author-1", "",
                "TEST_FIXTURE_MATERIAL_WRITE", "correlation-1");
        assertThatThrownBy(() -> service.promote("draft-1", "node_1", request("fixture"), incomplete))
                .isInstanceOfSatisfying(GraphNodeFixturePromotionException.class, failure ->
                        assertThat(failure.status()).isEqualTo(400));
        assertThat(materialWrites).isEmpty();
    }

    @Test
    void rejectsIdentityWithoutTenantBeforeReadingDraft() {
        IntegrationRequestContext missingTenant = identityWith("", "test");

        assertThatThrownBy(() -> service.promote("draft-1", "node_1", request("fixture"), missingTenant))
                .isInstanceOfSatisfying(GraphNodeFixturePromotionException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(400);
                    assertThat(failure.code()).isEqualTo("RG.VISUAL.PROMOTION.IDENTITY_REQUIRED");
                });

        verify(drafts, never()).find(any());
        verifyNoInteractions(operators, fixtureCatalog);
        assertThat(materialWrites).isEmpty();
    }

    @Test
    void rejectsIdentityWithoutEnvironmentBeforeReadingDraft() {
        IntegrationRequestContext missingEnvironment = identityWith("tenant-a", "");

        assertThatThrownBy(() -> service.promote("draft-1", "node_1", request("fixture"), missingEnvironment))
                .isInstanceOfSatisfying(GraphNodeFixturePromotionException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(400);
                    assertThat(failure.code()).isEqualTo("RG.VISUAL.PROMOTION.IDENTITY_REQUIRED");
                });

        verify(drafts, never()).find(any());
        verifyNoInteractions(operators, fixtureCatalog);
        assertThat(materialWrites).isEmpty();
    }

    @Test
    void returnsNotFoundForTenantOutsideDraftScopeBeforeMaterialWrite() {
        when(drafts.find("draft-1")).thenReturn(Optional.of(draft(Map.of())));
        IntegrationRequestContext foreignTenant = identityWith("tenant-b", "test");

        assertThatThrownBy(() -> service.promote("draft-1", "node_1", request("fixture"), foreignTenant))
                .isInstanceOfSatisfying(GraphNodeFixturePromotionException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(404);
                    assertThat(failure.code()).isEqualTo("RG.VISUAL.PROMOTION.DRAFT_NOT_FOUND");
                });

        verify(drafts).find("draft-1");
        verifyNoInteractions(operators, fixtureCatalog);
        assertThat(materialWrites).isEmpty();
    }

    @Test
    void returnsNotFoundForEnvironmentOutsideDraftScopeBeforeMaterialWrite() {
        when(drafts.find("draft-1")).thenReturn(Optional.of(draft(
                Map.of("node_1", new GraphDraft.NodeFixture(Map.of("score", 760))))));
        IntegrationRequestContext foreignEnvironment = identityWith("tenant-a", "production");

        assertThatThrownBy(() -> service.promote("draft-1", "node_1", request("fixture"), foreignEnvironment))
                .isInstanceOfSatisfying(GraphNodeFixturePromotionException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(404);
                    assertThat(failure.code()).isEqualTo("RG.VISUAL.PROMOTION.DRAFT_NOT_FOUND");
                });

        verify(drafts).find("draft-1");
        verifyNoInteractions(operators, fixtureCatalog);
        assertThat(materialWrites).isEmpty();
    }

    private GraphNodeFixturePromotionRequest request(String fixtureId) {
        return new GraphNodeFixturePromotionRequest(
                GraphNodeFixturePromotionRequest.SCHEMA_VERSION, fixtureId, "restricted", 3,
                List.of("/score"));
    }

    private IntegrationRequestContext identityWith(String tenantId, String environmentId) {
        return new IntegrationRequestContext(
                tenantId, "org-a", "project-a", environmentId, "sg", "USER", "author-1",
                "", FixtureMaterialWriterPurpose.VALUE, "correlation-1",
                Set.of(), "RESTRICTED", "");
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
        return resourceOperatorWithOutputs(1);
    }

    private static OperatorDefinition resourceOperatorWithOutputs(int outputCount) {
        return resourceOperatorWithSchema(SchemaEnvelope.object(
                Map.of("score", Map.of("type", "integer")), List.of("score")), outputCount);
    }

    private static OperatorDefinition resourceOperatorWithSchema(SchemaEnvelope output, int outputCount) {
        List<OperatorDefinition.Port> outputPorts = new java.util.ArrayList<>();
        for (int index = 0; index < outputCount; index++) {
            outputPorts.add(new OperatorDefinition.Port("payload-" + index, output, true, ""));
        }
        return new OperatorDefinition(
                "", "resource:applicant", "1.0.0", "",
                new OperatorDefinition.Display("Applicant profile", "", List.of()),
                new OperatorDefinition.Source(
                        "resource-descriptor", "applicant", "GET", "/applicants/{id}", true),
                new OperatorDefinition.Ports(List.of(), outputPorts),
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
