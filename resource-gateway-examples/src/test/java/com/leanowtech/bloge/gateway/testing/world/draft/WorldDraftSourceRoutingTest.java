package com.leanowtech.bloge.gateway.testing.world.draft;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldDraftSourceRoutingTest {
    @Test
    void metadataCanOnlyBeCreatedByServerOwnedPackageFactory() {
        assertThat(WorldDraftSourceAuthority.SourceMetadata.class.getConstructors()).isEmpty();
        assertThat(java.util.Arrays.stream(WorldDraftSourceAuthority.SourceMetadata.class.getMethods())
                .noneMatch(method -> method.getName().equals("sealed"))).isTrue();
        assertThatThrownBy(() -> new GovernedWorldDraftSourceRouter(List.of())).isInstanceOf(
                WorldDraftCandidateException.class);
    }

    @Test
    void everyGovernedSourceKindUsesTheAuthorizedSourcePort() {
        Map<WorldDraftSourceRef.Kind, WorldDraftTestSupport.Fixture> fixtures = new EnumMap<>(WorldDraftSourceRef.Kind.class);
        for (WorldDraftSourceRef.Kind kind : WorldDraftSourceRef.Kind.values()) {
            WorldDraftSourceRef source = WorldDraftTestSupport.source(kind, WorldDraftTestSupport.TENANT,
                    kind.name().toLowerCase(java.util.Locale.ROOT));
            fixtures.put(kind, WorldDraftTestSupport.fixture(source, WorldDraftTestSupport.policy(),
                    WorldDraftTestSupport.NOW.plusSeconds(60)));
        }
        Set<WorldDraftSourceRef.Kind> routed = EnumSet.noneOf(WorldDraftSourceRef.Kind.class);
        AtomicInteger reads = new AtomicInteger();
        List<WorldDraftSourceAdapter> adapters = fixtures.keySet().stream().<WorldDraftSourceAdapter>map(kind -> new WorldDraftSourceAdapter() {
            @Override public WorldDraftSourceRef.Kind kind() { return kind; }
            @Override public WorldDraftSourceAuthority.SourceMetadata inspect(WorldDraftSourceRef source,
                                                                                WorldDraftCandidateService.Access access) {
                routed.add(kind);
                return fixtures.get(kind).metadata();
            }
            @Override public WorldDraftSourceAuthority.SourcePayload read(
                    WorldDraftSourceAuthority.SourceMetadata metadata, WorldDraftCandidateService.Access access) {
                reads.incrementAndGet();
                return fixtures.get(kind).payload();
            }
        }).toList();
        WorldDraftSourceAuthority authority = new GovernedWorldDraftSourceRouter(adapters);
        WorldDraftCandidateService service = new WorldDraftCandidateService(authority,
                new InMemoryWorldDraftCandidateRepository(), request -> {
                    throw new WorldDraftCandidateException(WorldDraftCandidateException.Code.MATERIALIZATION_INVALID);
                }, Clock.fixed(WorldDraftTestSupport.NOW, ZoneOffset.UTC));
        for (WorldDraftSourceRef.Kind kind : WorldDraftSourceRef.Kind.values()) {
            WorldDraftTestSupport.Fixture fixture = fixtures.get(kind);
            service.capture("candidate-" + kind.name(), WorldDraftTestSupport.ACCESS, fixture.source());
        }
        assertThat(routed).containsExactlyInAnyOrder(WorldDraftSourceRef.Kind.values());
        assertThat(reads).hasValue(WorldDraftSourceRef.Kind.values().length);
    }
}
