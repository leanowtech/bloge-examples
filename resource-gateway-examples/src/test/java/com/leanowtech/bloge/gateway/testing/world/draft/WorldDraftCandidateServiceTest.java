package com.leanowtech.bloge.gateway.testing.world.draft;

import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorldDraftCandidateServiceTest {
    private static final String TENANT = "tenant-a";
    private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");
    private static final WorldDraftCandidateService.Access ACCESS = new WorldDraftCandidateService.Access(
            TENANT, WorldDraftCandidateService.PURPOSE, "reviewer", "capture-1");

    @Test
    void capturesAfterMetadataGatesAndCandidateHasNoPayload() {
        AtomicInteger reads = new AtomicInteger();
        Fixture fixture = fixture(source(TENANT), WorldDraftRedactionPolicy.identity("policy"), NOW.plusSeconds(60));
        WorldDraftCandidate candidate = service(fixture, reads).capture("candidate-1", ACCESS, fixture.source);

        assertThat(candidate.state()).isEqualTo(WorldDraftState.CAPTURED);
        assertThat(candidate.toString()).doesNotContain("secret", "customer@example.com");
        assertThat(reads).hasValue(1);
    }

    @Test
    void crossTenantExpiredAndTamperedMetadataReadZeroPayload() {
        AtomicInteger reads = new AtomicInteger();
        Fixture expired = fixture(source(TENANT), WorldDraftRedactionPolicy.identity("policy"), NOW.minusSeconds(1));
        assertCode(() -> service(expired, reads).capture("expired", ACCESS, expired.source),
                WorldDraftCandidateException.Code.SOURCE_EXPIRED);

        Fixture valid = fixture(source(TENANT), WorldDraftRedactionPolicy.identity("policy"), NOW.plusSeconds(60));
        WorldDraftSourceAuthority.SourceMetadata tampered = WorldDraftSourceAuthority.SourceMetadata.unsafeForTest(
                valid.source, TENANT, true, true, valid.metadata.expiresAt(), valid.metadata.requestSchema(),
                valid.metadata.responseSchema(), valid.metadata.schemaFingerprint(),
                valid.metadata.redactionPolicyFingerprint(), valid.metadata.requestFingerprint(),
                valid.metadata.responseFingerprint(), FP("wrong-metadata"));
        assertCode(() -> service(new Fixture(valid.source, tampered, valid.payload, valid.policy), reads)
                        .capture("tampered", ACCESS, valid.source),
                WorldDraftCandidateException.Code.SOURCE_INTEGRITY);

        WorldDraftSourceRef foreign = source("tenant-b");
        assertCode(() -> service(fixture(foreign, valid.policy, NOW.plusSeconds(60)), reads)
                        .capture("foreign", ACCESS, foreign),
                WorldDraftCandidateException.Code.SOURCE_NOT_AUTHORIZED);
        assertThat(reads).hasValue(0);
    }

    @Test
    void redactionMustBeSafeBeforeReviewAndFinalScanCatchesReplacement() {
        AtomicInteger reads = new AtomicInteger();
        WorldDraftSourceRef source = source(TENANT);
        WorldDraftRedactionPolicy policy = new WorldDraftRedactionPolicy("policy",
                List.of(new WorldDraftRedactionPolicy.FieldRule("/secret", WorldDraftRedactionPolicy.Action.DROP, null)),
                List.of());
        Fixture fixture = fixture(source, policy, NOW.plusSeconds(60));
        WorldDraftCandidateService service = service(fixture, reads);
        WorldDraftCandidate captured = service.capture("candidate-2", ACCESS, source);
        WorldDraftCandidate redacted = service.redact("candidate-2", captured.revision(), ACCESS, policy);
        assertThat(redacted.state()).isEqualTo(WorldDraftState.REDACTION_REQUIRED);
        assertThat(redacted.redactionReport().unknownFieldCount()).isEqualTo(2);
        assertThat(redacted.redactionReport().safe()).isTrue();
        WorldDraftCandidate ready = service.markReviewReady("candidate-2", redacted.revision(), ACCESS);
        assertThat(ready.state()).isEqualTo(WorldDraftState.REVIEW_READY);

        WorldDraftRedactionPolicy unsafe = new WorldDraftRedactionPolicy("policy-unsafe",
                List.of(new WorldDraftRedactionPolicy.FieldRule("/secret",
                        WorldDraftRedactionPolicy.Action.FIXED_REPLACEMENT, "secret-value")), List.of());
        Fixture unsafeFixture = fixture(source, unsafe, NOW.plusSeconds(60));
        WorldDraftCandidateService unsafeService = service(unsafeFixture, reads);
        WorldDraftCandidate unsafeCandidate = unsafeService.capture("candidate-3", ACCESS, source);
        WorldDraftCandidate unsafeRedaction = unsafeService.redact("candidate-3", unsafeCandidate.revision(),
                ACCESS, unsafe);
        assertThat(unsafeRedaction.redactionReport().safe()).isFalse();
        assertCode(() -> unsafeService.markReviewReady("candidate-3", unsafeRedaction.revision(), ACCESS),
                WorldDraftCandidateException.Code.REDACTION_REQUIRED);
    }

    @Test
    void approvalMaterializationAndPublicationAreIndependentBoundedTransitions() {
        AtomicInteger reads = new AtomicInteger();
        WorldDraftSourceRef source = source(TENANT);
        WorldDraftRedactionPolicy policy = new WorldDraftRedactionPolicy("policy",
                List.of(new WorldDraftRedactionPolicy.FieldRule("/secret", WorldDraftRedactionPolicy.Action.DROP, null)),
                List.of());
        Fixture fixture = fixture(source, policy, NOW.plusSeconds(60));
        ResourceWorldModel baseWorld = mock(ResourceWorldModel.class);
        when(baseWorld.tenantId()).thenReturn(TENANT);
        when(baseWorld.worldModelId()).thenReturn("world");
        when(baseWorld.revision()).thenReturn(1L);
        ResourceWorldModel draftWorld = mock(ResourceWorldModel.class);
        when(draftWorld.tenantId()).thenReturn(TENANT);
        when(draftWorld.worldModelId()).thenReturn("world");
        when(draftWorld.revision()).thenReturn(2L);
        when(draftWorld.fingerprint()).thenReturn(FP("draft-world"));
        WorldDraftMaterializer materializer = request -> new WorldDraftMaterializer.MaterializedDraft(
                request.candidate(), draftWorld, new WorldDraftRule(request.candidate().schemaFingerprint(),
                request.candidate().effectiveRequestFingerprint(), request.candidate().effectiveResponseFingerprint(),
                null, request.candidate().redactedPayloadRef()), false);
        WorldDraftCandidateService service = new WorldDraftCandidateService(authority(fixture, reads),
                new InMemoryWorldDraftCandidateRepository(), WorldDraftRedactor.schemaGuided(), materializer,
                Clock.fixed(NOW, ZoneOffset.UTC));
        WorldDraftCandidate captured = service.capture("candidate-4", ACCESS, source);
        WorldDraftCandidate redacted = service.redact("candidate-4", captured.revision(), ACCESS, policy);
        WorldDraftRedactedPayloadRef artifact = redacted.redactedPayloadRef();
        assertThat(artifact.artifactRevision()).isEqualTo(redacted.revision());
        WorldDraftCandidate ready = service.markReviewReady("candidate-4", redacted.revision(), ACCESS);
        assertThat(ready.revision()).isGreaterThan(artifact.artifactRevision());
        assertThat(ready.redactedPayloadRef()).isEqualTo(artifact);
        WorldDraftCandidate approved = service.approve("candidate-4", ready.revision(), ACCESS);
        assertThat(approved.redactedPayloadRef()).isEqualTo(artifact);
        WorldDraftMaterializer.MaterializedDraft draft = service.materialize("candidate-4", approved.revision(),
                ACCESS, baseWorld);
        WorldDraftCandidate materialized = service.find("candidate-4", ACCESS).orElseThrow();
        assertThat(materialized.redactedPayloadRef()).isEqualTo(artifact);

        assertThat(draft.published()).isFalse();
        assertThat(materialized.state()).isEqualTo(WorldDraftState.MATERIALIZED_DRAFT);
        WorldDraftCandidate published = service.publish("candidate-4", materialized.revision(), ACCESS);
        assertThat(published.state()).isEqualTo(WorldDraftState.PUBLISHED);
        assertThat(published.redactedPayloadRef()).isEqualTo(artifact);
    }

    @Test
    void staleRevisionIsRejectedByCasAndNoGenericTransitionBypassExists() {
        AtomicInteger reads = new AtomicInteger();
        Fixture fixture = fixture(source(TENANT), WorldDraftRedactionPolicy.identity("policy"), NOW.plusSeconds(60));
        WorldDraftCandidateService service = service(fixture, reads);
        WorldDraftCandidate captured = service.capture("candidate-5", ACCESS, fixture.source);
        service.redact("candidate-5", captured.revision(), ACCESS, fixture.policy);
        assertCode(() -> service.markReviewReady("candidate-5", captured.revision(), ACCESS),
                WorldDraftCandidateException.Code.CAS_CONFLICT);
        assertThat(java.util.Arrays.stream(WorldDraftCandidateService.class.getMethods())
                .noneMatch(method -> method.getName().equals("transition"))).isTrue();
    }

    private static WorldDraftCandidateService service(Fixture fixture, AtomicInteger reads) {
        return new WorldDraftCandidateService(authority(fixture, reads),
                new InMemoryWorldDraftCandidateRepository(), WorldDraftRedactor.schemaGuided(),
                request -> { throw new WorldDraftCandidateException(
                        WorldDraftCandidateException.Code.MATERIALIZATION_INVALID); },
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static WorldDraftSourceAuthority authority(Fixture fixture, AtomicInteger reads) {
        return new WorldDraftSourceAuthority() {
            public SourceMetadata inspect(WorldDraftSourceRef source, WorldDraftCandidateService.Access access) {
                return fixture.metadata;
            }
            public SourcePayload read(SourceMetadata metadata, WorldDraftCandidateService.Access access) {
                reads.incrementAndGet();
                return fixture.payload;
            }
        };
    }

    private static Fixture fixture(WorldDraftSourceRef source, WorldDraftRedactionPolicy policy, Instant expires) {
        Map<String, Object> request = Map.of("safe", "value", "secret", "secret", "unknown", "drop");
        Map<String, Object> response = Map.of("result", "ok", "unknown", "drop");
        WorldDraftSourceAuthority.SourcePayload payload = new WorldDraftSourceAuthority.SourcePayload(request, response);
        SchemaEnvelope schema = WorldDraftTestSupport.classifiedObject(Map.of("safe", WorldDraftTestSupport.publicString(),
                "secret", WorldDraftTestSupport.publicString()), List.of("safe"));
        SchemaEnvelope responseSchema = WorldDraftTestSupport.classifiedObject(
                Map.of("result", WorldDraftTestSupport.publicString()), List.of("result"));
        WorldDraftSourceAuthority.SourceMetadata metadata = WorldDraftSourceAuthority.SourceMetadata.sealed(source,
                source.tenantId(), true, true, expires, schema, responseSchema, policy.fingerprint(),
                payload.requestFingerprint(), payload.responseFingerprint());
        return new Fixture(source, metadata, payload, policy);
    }

    private record Fixture(WorldDraftSourceRef source, WorldDraftSourceAuthority.SourceMetadata metadata,
                           WorldDraftSourceAuthority.SourcePayload payload, WorldDraftRedactionPolicy policy) { }

    private static WorldDraftSourceRef source(String tenant) {
        return WorldDraftSourceRef.exact(WorldDraftSourceRef.Kind.GOLDEN_CAPTURE, tenant,
                "capture-1", 1, FP("source"));
    }
    private static String FP(String seed) {
        return com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint.fromMaterial(Map.of("seed", seed));
    }
    private static void assertCode(ThrowingCallable call, WorldDraftCandidateException.Code code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(WorldDraftCandidateException.class,
                error -> assertThat(error.code()).isEqualTo(code));
    }
}
