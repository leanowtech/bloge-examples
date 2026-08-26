package com.leanowtech.bloge.gateway.testing.world.draft;

import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class WorldDraftTestSupport {
    static final String TENANT = "tenant-a";
    static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");
    static final WorldDraftCandidateService.Access ACCESS = new WorldDraftCandidateService.Access(
            TENANT, WorldDraftCandidateService.PURPOSE, "reviewer", "draft-test");

    private WorldDraftTestSupport() { }

    static Fixture fixture(WorldDraftSourceRef source, WorldDraftRedactionPolicy policy, Instant expires) {
        Map<String, Object> request = Map.of("safe", "value", "secret", "secret");
        Map<String, Object> response = Map.of("result", "ok");
        WorldDraftSourceAuthority.SourcePayload payload = new WorldDraftSourceAuthority.SourcePayload(request, response);
        return new Fixture(source, metadata(source, policy, expires, requestSchema(), responseSchema(), payload), payload, policy);
    }

    static WorldDraftSourceAuthority.SourceMetadata metadata(WorldDraftSourceRef source,
                                                              WorldDraftRedactionPolicy policy,
                                                              Instant expires,
                                                              SchemaEnvelope requestSchema,
                                                              SchemaEnvelope responseSchema,
                                                              WorldDraftSourceAuthority.SourcePayload payload) {
        return WorldDraftSourceAuthority.SourceMetadata.sealed(source, source.tenantId(), true, true, expires,
                requestSchema, responseSchema, policy.fingerprint(), payload.requestFingerprint(), payload.responseFingerprint());
    }

    static WorldDraftSourceRef source(WorldDraftSourceRef.Kind kind, String tenant, String id) {
        return WorldDraftSourceRef.exact(kind, tenant, id, 1, fp(kind.name() + ":" + tenant + ":" + id));
    }

    static WorldDraftSourceRef source(String tenant) {
        return source(WorldDraftSourceRef.Kind.GOLDEN_CAPTURE, tenant, "capture-1");
    }

    static WorldDraftRedactionPolicy policy() {
        return new WorldDraftRedactionPolicy("policy",
                List.of(new WorldDraftRedactionPolicy.FieldRule("/secret",
                        WorldDraftRedactionPolicy.Action.DROP, null)), List.of());
    }

    static SchemaEnvelope requestSchema() {
        return classifiedObject(Map.of("safe", publicString(), "secret", publicString()), List.of("safe"));
    }

    static SchemaEnvelope responseSchema() {
        return classifiedObject(Map.of("result", publicString()), List.of("result"));
    }

    static Map<String, Object> publicString() {
        return Map.of("type", "string", "x-data-classification", "PUBLIC");
    }

    static SchemaEnvelope classifiedObject(Map<String, Object> properties, List<String> required) {
        return new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                "type", "object", "properties", properties, "required", required,
                "additionalProperties", false));
    }

    static WorldDraftSourceAuthority authority(Fixture fixture, AtomicInteger reads) {
        return new WorldDraftSourceAuthority() {
            @Override
            public SourceMetadata inspect(WorldDraftSourceRef source, WorldDraftCandidateService.Access access) {
                return fixture.metadata();
            }

            @Override
            public SourcePayload read(SourceMetadata metadata, WorldDraftCandidateService.Access access) {
                reads.incrementAndGet();
                return fixture.payload();
            }
        };
    }

    static WorldDraftCandidateService service(Fixture fixture, AtomicInteger reads,
                                               WorldDraftMaterializer materializer) {
        return new WorldDraftCandidateService(authority(fixture, reads),
                new InMemoryWorldDraftCandidateRepository(), WorldDraftRedactor.schemaGuided(), materializer,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static ResourceWorldModel world(String id, long revision) {
        ResourceWorldModel world = mock(ResourceWorldModel.class);
        when(world.tenantId()).thenReturn(TENANT);
        when(world.worldModelId()).thenReturn(id);
        when(world.revision()).thenReturn(revision);
        when(world.fingerprint()).thenReturn(fp(id + ":" + revision));
        return world;
    }

    static String fp(String seed) {
        return VisualBundleFingerprint.fromMaterial(Map.of("seed", seed));
    }

    static String payloadFp(Object value) {
        return ProtocolFingerprint.of(WorldDraftSourceAuthority.MAPPER, value);
    }

    record Fixture(WorldDraftSourceRef source, WorldDraftSourceAuthority.SourceMetadata metadata,
                   WorldDraftSourceAuthority.SourcePayload payload, WorldDraftRedactionPolicy policy) { }
}
