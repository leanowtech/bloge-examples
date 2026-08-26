package com.leanowtech.bloge.gateway.testing.world.draft;

import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldDraftCandidateSecurityTest {
    @Test
    void approvalIsInvalidatedBySourceSchemaPolicyAndMetadataRefreshDrift() {
        WorldDraftTestSupport.Fixture fixture = fixture();
        WorldDraftSourceRef changedSource = WorldDraftTestSupport.source(
                WorldDraftSourceRef.Kind.GOLDEN_CAPTURE, WorldDraftTestSupport.TENANT, "changed-source");
        assertDrift(fixture, WorldDraftTestSupport.metadata(changedSource, fixture.policy(), expires(),
                WorldDraftTestSupport.requestSchema(), WorldDraftTestSupport.responseSchema(), fixture.payload()),
                WorldDraftCandidateException.Code.SOURCE_INTEGRITY);

        SchemaEnvelope changedSchema = SchemaEnvelope.object(Map.of("safe", Map.of("type", "string"),
                "newField", Map.of("type", "string")), List.of("safe"));
        assertDrift(fixture, WorldDraftTestSupport.metadata(fixture.source(), fixture.policy(), expires(),
                changedSchema, WorldDraftTestSupport.responseSchema(), fixture.payload()),
                WorldDraftCandidateException.Code.APPROVAL_STALE);

        WorldDraftRedactionPolicy changedPolicy = new WorldDraftRedactionPolicy("policy-v2",
                List.of(new WorldDraftRedactionPolicy.FieldRule("/secret",
                        WorldDraftRedactionPolicy.Action.DROP, null)), List.of());
        assertDrift(fixture, WorldDraftTestSupport.metadata(fixture.source(), changedPolicy, expires(),
                WorldDraftTestSupport.requestSchema(), WorldDraftTestSupport.responseSchema(), fixture.payload()),
                WorldDraftCandidateException.Code.APPROVAL_STALE);

        assertDrift(fixture, WorldDraftTestSupport.metadata(fixture.source(), fixture.policy(),
                WorldDraftTestSupport.NOW.plusSeconds(120), WorldDraftTestSupport.requestSchema(),
                WorldDraftTestSupport.responseSchema(), fixture.payload()),
                WorldDraftCandidateException.Code.APPROVAL_STALE);
    }

    @Test
    void redactionGatesExpiredCrossTenantTamperedAndDeniedSourcesBeforePayloadRead() {
        assertExpiredRedactionIsGated();
        assertCrossTenantRedactionIsGated();
        assertTamperedRedactionIsGated();
        assertDeniedPolicyRedactionIsGated();
    }

    private static void assertDrift(WorldDraftTestSupport.Fixture fixture,
                                    WorldDraftSourceAuthority.SourceMetadata drift,
                                    WorldDraftCandidateException.Code expected) {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger materializations = new AtomicInteger();
        AtomicReference<WorldDraftSourceAuthority.SourceMetadata> metadata = new AtomicReference<>(fixture.metadata());
        ResourceWorldModel base = WorldDraftTestSupport.world("world", 1);
        ResourceWorldModel draftWorld = WorldDraftTestSupport.world("world", 2);
        WorldDraftMaterializer materializer = request -> {
            materializations.incrementAndGet();
            return new WorldDraftMaterializer.MaterializedDraft(request.candidate(), draftWorld,
                    new WorldDraftRule(request.candidate().schemaFingerprint(), request.candidate().effectiveRequestFingerprint(),
                            request.candidate().effectiveResponseFingerprint(), null, request.candidate().redactedPayloadRef()), false);
        };
        WorldDraftCandidateService service = service(fixture, reads, metadata, materializer);
        WorldDraftCandidate captured = service.capture("drift", WorldDraftTestSupport.ACCESS, fixture.source());
        WorldDraftCandidate redacted = service.redact("drift", captured.revision(),
                WorldDraftTestSupport.ACCESS, fixture.policy());
        WorldDraftCandidate ready = service.markReviewReady("drift", redacted.revision(),
                WorldDraftTestSupport.ACCESS);
        WorldDraftCandidate approved = service.approve("drift", ready.revision(),
                WorldDraftTestSupport.ACCESS);
        metadata.set(drift);
        assertCode(() -> service.materialize("drift", approved.revision(), WorldDraftTestSupport.ACCESS, base), expected);
        assertThat(materializations).hasValue(0);
        assertThat(reads).hasValue(2);
    }

    private static WorldDraftCandidateService service(WorldDraftTestSupport.Fixture fixture, AtomicInteger reads,
                                                       AtomicReference<WorldDraftSourceAuthority.SourceMetadata> metadata) {
        return service(fixture, reads, metadata, request -> {
            throw new WorldDraftCandidateException(WorldDraftCandidateException.Code.MATERIALIZATION_INVALID);
        });
    }

    private static WorldDraftCandidateService service(WorldDraftTestSupport.Fixture fixture, AtomicInteger reads,
                                                       AtomicReference<WorldDraftSourceAuthority.SourceMetadata> metadata,
                                                       WorldDraftMaterializer materializer) {
        WorldDraftSourceAuthority authority = new WorldDraftSourceAuthority() {
            @Override public SourceMetadata inspect(WorldDraftSourceRef source, WorldDraftCandidateService.Access access) {
                return metadata.get();
            }
            @Override public SourcePayload read(SourceMetadata metadata, WorldDraftCandidateService.Access access) {
                reads.incrementAndGet();
                return fixture.payload();
            }
        };
        return new WorldDraftCandidateService(authority, new InMemoryWorldDraftCandidateRepository(),
                WorldDraftRedactor.schemaGuided(), materializer,
                java.time.Clock.fixed(WorldDraftTestSupport.NOW, java.time.ZoneOffset.UTC));
    }

    private static void assertExpiredRedactionIsGated() {
        WorldDraftTestSupport.Fixture fixture = fixture();
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<WorldDraftSourceAuthority.SourceMetadata> metadata = new AtomicReference<>(fixture.metadata());
        WorldDraftCandidateService service = service(fixture, reads, metadata);
        WorldDraftCandidate captured = service.capture("expired", WorldDraftTestSupport.ACCESS, fixture.source());
        metadata.set(WorldDraftTestSupport.metadata(fixture.source(), fixture.policy(),
                WorldDraftTestSupport.NOW.minusSeconds(1), WorldDraftTestSupport.requestSchema(),
                WorldDraftTestSupport.responseSchema(), fixture.payload()));
        assertCode(() -> service.redact("expired", captured.revision(), WorldDraftTestSupport.ACCESS, fixture.policy()),
                WorldDraftCandidateException.Code.SOURCE_EXPIRED);
        assertThat(reads).hasValue(1);
    }

    private static void assertCrossTenantRedactionIsGated() {
        WorldDraftTestSupport.Fixture fixture = fixture();
        AtomicInteger reads = new AtomicInteger();
        WorldDraftCandidateService service = service(fixture, reads, new AtomicReference<>(fixture.metadata()));
        WorldDraftCandidate captured = service.capture("foreign", WorldDraftTestSupport.ACCESS, fixture.source());
        WorldDraftCandidateService.Access foreign = new WorldDraftCandidateService.Access("tenant-b",
                WorldDraftCandidateService.PURPOSE, "reviewer", "foreign");
        assertCode(() -> service.redact("foreign", captured.revision(), foreign, fixture.policy()),
                WorldDraftCandidateException.Code.CANDIDATE_NOT_FOUND);
        assertThat(reads).hasValue(1);
    }

    private static void assertTamperedRedactionIsGated() {
        WorldDraftTestSupport.Fixture fixture = fixture();
        AtomicInteger reads = new AtomicInteger();
        WorldDraftSourceAuthority.SourceMetadata tampered = WorldDraftSourceAuthority.SourceMetadata.unsafeForTest(
                fixture.source(), fixture.source().tenantId(), true, true, expires(), fixture.metadata().requestSchema(),
                fixture.metadata().responseSchema(), fixture.metadata().schemaFingerprint(),
                fixture.metadata().redactionPolicyFingerprint(), fixture.metadata().requestFingerprint(),
                fixture.metadata().responseFingerprint(), WorldDraftTestSupport.fp("tampered-metadata"));
        AtomicReference<WorldDraftSourceAuthority.SourceMetadata> metadata = new AtomicReference<>(fixture.metadata());
        WorldDraftCandidateService service = service(fixture, reads, metadata);
        WorldDraftCandidate captured = service.capture("tampered", WorldDraftTestSupport.ACCESS, fixture.source());
        metadata.set(tampered);
        assertCode(() -> service.redact("tampered", captured.revision(), WorldDraftTestSupport.ACCESS, fixture.policy()),
                WorldDraftCandidateException.Code.SOURCE_INTEGRITY);
        assertThat(reads).hasValue(1);
    }

    private static void assertDeniedPolicyRedactionIsGated() {
        WorldDraftTestSupport.Fixture fixture = fixture();
        AtomicInteger reads = new AtomicInteger();
        WorldDraftCandidateService service = service(fixture, reads, new AtomicReference<>(fixture.metadata()));
        WorldDraftCandidate captured = service.capture("policy-denied", WorldDraftTestSupport.ACCESS, fixture.source());
        WorldDraftRedactionPolicy denied = WorldDraftRedactionPolicy.identity("different-policy");
        assertCode(() -> service.redact("policy-denied", captured.revision(), WorldDraftTestSupport.ACCESS, denied),
                WorldDraftCandidateException.Code.SOURCE_POLICY_DENIED);
        assertThat(reads).hasValue(1);
    }

    private static WorldDraftTestSupport.Fixture fixture() {
        return WorldDraftTestSupport.fixture(WorldDraftTestSupport.source(WorldDraftTestSupport.TENANT),
                WorldDraftTestSupport.policy(), expires());
    }

    private static Instant expires() { return WorldDraftTestSupport.NOW.plusSeconds(60); }

    private static void assertCode(ThrowingCallable call, WorldDraftCandidateException.Code code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(WorldDraftCandidateException.class,
                error -> assertThat(error.code()).isEqualTo(code));
    }
}
