package com.leanowtech.bloge.gateway.testing.world.draft;

import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;
import com.leanowtech.bloge.gateway.testing.world.Scenario;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldDraftCandidateBoundaryTest {
    private static final String MARKER = "customer@example.com secret-value";

    @Test
    void publicCaptureApiAcceptsOnlyGovernedSourceReference() {
        assertThat(Arrays.stream(WorldDraftCandidateService.class.getMethods())
                .filter(method -> method.getName().equals("capture"))
                .map(Method::getParameterTypes)
                .flatMap(Arrays::stream)
                .noneMatch(type -> Map.class.isAssignableFrom(type))).isTrue();
    }

    @Test
    void publicPromotionApiAcceptsAccessAndNotCallerSuppliedReceipts() {
        assertThat(Arrays.stream(WorldDraftCandidateService.class.getMethods())
                .filter(method -> method.getName().equals("approve") || method.getName().equals("publish"))
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .noneMatch(type -> type == WorldDraftApproval.class || type == WorldDraftPublicationReceipt.class))
                .isTrue();
    }

    @Test
    void redactedArtifactReferenceCannotBeReplacedAfterRedaction() {
        WorldDraftTestSupport.Fixture fixture = WorldDraftTestSupport.fixture(
                WorldDraftTestSupport.source(WorldDraftSourceRef.Kind.GOLDEN_CAPTURE,
                        WorldDraftTestSupport.TENANT, "ref-boundary"), WorldDraftTestSupport.policy(),
                WorldDraftTestSupport.NOW.plusSeconds(60));
        AtomicInteger reads = new AtomicInteger();
        WorldDraftCandidateService service = new WorldDraftCandidateService(
                WorldDraftTestSupport.authority(fixture, reads), new InMemoryWorldDraftCandidateRepository(),
                WorldDraftRedactor.schemaGuided(), request -> {
                    throw new WorldDraftCandidateException(WorldDraftCandidateException.Code.MATERIALIZATION_INVALID);
                }, java.time.Clock.fixed(WorldDraftTestSupport.NOW, java.time.ZoneOffset.UTC));
        WorldDraftCandidate captured = service.capture("ref-boundary", WorldDraftTestSupport.ACCESS, fixture.source());
        WorldDraftCandidate redacted = service.redact("ref-boundary", captured.revision(),
                WorldDraftTestSupport.ACCESS, fixture.policy());
        WorldDraftRedactedPayloadRef changed = WorldDraftRedactedPayloadRef.of(redacted.tenantId(),
                redacted.candidateId(), redacted.redactedPayloadRef().artifactRevision(),
                new WorldDraftRedactedPayload(Map.of("safe", "changed"), Map.of("result", "ok")));
        assertThatThrownBy(() -> redacted.next(WorldDraftState.REVIEW_READY, "", "", changed,
                redacted.redactionReportFingerprint(), redacted.redactionReport()))
                .isInstanceOf(WorldDraftCandidateException.class);
    }

    @Test
    void errorsReportsProvenanceAndMaterializationToStringArePayloadFree() {
        WorldDraftTestSupport.Fixture fixture = WorldDraftTestSupport.fixture(
                WorldDraftTestSupport.source(WorldDraftTestSupport.TENANT), WorldDraftTestSupport.policy(),
                WorldDraftTestSupport.NOW.plusSeconds(60));
        AtomicInteger reads = new AtomicInteger();
        ResourceWorldModel base = WorldDraftTestSupport.world("world", 1);
        ResourceWorldModel draftWorld = WorldDraftTestSupport.world("world", 2);
        WorldDraftMaterializer materializer = request -> new WorldDraftMaterializer.MaterializedDraft(
                request.candidate(), draftWorld, new WorldDraftRule(request.candidate().schemaFingerprint(),
                request.candidate().effectiveRequestFingerprint(), request.candidate().effectiveResponseFingerprint(),
                null, request.candidate().redactedPayloadRef()), false);
        WorldDraftCandidateService service = new WorldDraftCandidateService(
                WorldDraftTestSupport.authority(fixture, reads), new InMemoryWorldDraftCandidateRepository(),
                WorldDraftRedactor.schemaGuided(), materializer,
                Clock.fixed(WorldDraftTestSupport.NOW, ZoneOffset.UTC));
        WorldDraftCandidate captured = service.capture("payload-free", WorldDraftTestSupport.ACCESS, fixture.source());
        WorldDraftCandidate redacted = service.redact("payload-free", captured.revision(),
                WorldDraftTestSupport.ACCESS, fixture.policy());
        WorldDraftCandidate ready = service.markReviewReady("payload-free", redacted.revision(),
                WorldDraftTestSupport.ACCESS);
        WorldDraftCandidate approved = service.approve("payload-free", ready.revision(),
                WorldDraftTestSupport.ACCESS);
        WorldDraftMaterializer.MaterializedDraft result = service.materialize("payload-free", approved.revision(),
                WorldDraftTestSupport.ACCESS, base);
        String visible = captured + "\n" + redacted.redactionReport() + "\n" + result + "\n"
                + result.provenance() + "\n" + result.rule() + "\n" + result.candidate();
        assertThat(visible).doesNotContain(MARKER, "secret");

        WorldDraftSourceAuthority throwing = new WorldDraftSourceAuthority() {
            @Override public SourceMetadata inspect(WorldDraftSourceRef source, WorldDraftCandidateService.Access access) {
                return fixture.metadata();
            }
            @Override public SourcePayload read(SourceMetadata metadata, WorldDraftCandidateService.Access access) {
                throw new IllegalStateException(MARKER);
            }
        };
        WorldDraftCandidateService failed = new WorldDraftCandidateService(throwing,
                new InMemoryWorldDraftCandidateRepository(), WorldDraftRedactor.schemaGuided(), materializer,
                Clock.fixed(WorldDraftTestSupport.NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> failed.capture("error", WorldDraftTestSupport.ACCESS, fixture.source()))
                .isInstanceOf(WorldDraftCandidateException.class)
                .hasMessageNotContaining(MARKER);
    }

    @Test
    void materializedScenarioCannotCarryContextStateInitOrExpectations() {
        WorldDraftRedactedPayloadRef payloadRef = WorldDraftRedactedPayloadRef.of(
                WorldDraftTestSupport.TENANT, "scenario-boundary", 1,
                new WorldDraftRedactedPayload(Map.of("safe", "request"), Map.of("result", "response")));
        WorldDraftCandidate candidate = new WorldDraftCandidate("scenario-boundary", 3,
                WorldDraftState.APPROVED, WorldDraftTestSupport.TENANT,
                WorldDraftTestSupport.source(WorldDraftSourceRef.Kind.GOLDEN_CAPTURE,
                        WorldDraftTestSupport.TENANT, "scenario-boundary"),
                WorldDraftTestSupport.fp("metadata"), WorldDraftTestSupport.fp("schema"),
                WorldDraftTestSupport.fp("policy"), WorldDraftTestSupport.fp("request"),
                WorldDraftTestSupport.fp("response"), payloadRef, WorldDraftTestSupport.fp("report"),
                WorldDraftRedactionReport.notProcessed(), WorldDraftTestSupport.fp("approval"),
                WorldDraftTestSupport.fp("materialization"));
        ResourceWorldModel world = WorldDraftTestSupport.world("scenario-world", 2);
        WorldDraftRule rule = new WorldDraftRule(WorldDraftTestSupport.fp("schema"),
                WorldDraftTestSupport.fp("input"), WorldDraftTestSupport.fp("output"));
        Scenario contextPayload = org.mockito.Mockito.mock(Scenario.class);
        org.mockito.Mockito.when(contextPayload.context()).thenReturn(Map.of("payload", MARKER));
        org.mockito.Mockito.when(contextPayload.stateInit()).thenReturn(Scenario.WorldStateInit.EMPTY);
        org.mockito.Mockito.when(contextPayload.expect()).thenReturn(java.util.List.of());
        Scenario.WorldModelRef worldRef = org.mockito.Mockito.mock(Scenario.WorldModelRef.class);
        String worldId = world.worldModelId();
        long worldRevision = world.revision();
        String worldFingerprint = world.fingerprint();
        org.mockito.Mockito.when(contextPayload.tenantId()).thenReturn(WorldDraftTestSupport.TENANT);
        org.mockito.Mockito.when(contextPayload.world()).thenReturn(worldRef);
        org.mockito.Mockito.when(worldRef.worldModelId()).thenReturn(worldId);
        org.mockito.Mockito.when(worldRef.revision()).thenReturn(worldRevision);
        org.mockito.Mockito.when(worldRef.fingerprint()).thenReturn(worldFingerprint);
        WorldDraftMaterializer.MaterializedDraft draft = new WorldDraftMaterializer.MaterializedDraft(
                candidate, world, rule, Optional.of(contextPayload),
                WorldDraftProvenance.of(candidate, rule), false);

        assertThatThrownBy(() -> new WorldDraftAssetRepository.StoredAsset(draft))
                .isInstanceOf(WorldDraftCandidateException.class)
                .extracting(error -> ((WorldDraftCandidateException) error).code())
                .isEqualTo(WorldDraftCandidateException.Code.MATERIALIZATION_INVALID);
    }
}
