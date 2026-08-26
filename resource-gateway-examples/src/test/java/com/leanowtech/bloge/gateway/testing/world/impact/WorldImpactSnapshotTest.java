package com.leanowtech.bloge.gateway.testing.world.impact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldImpactSnapshotTest {
    private static final String TENANT = "tenant-a";
    private static final String TARGET = fp('a');
    private static final String SCENARIO = fp('b');
    private static final String WORLD = fp('c');
    private static final String CONTRACT = fp('d');
    private static final String SLICE = fp('e');
    private static final String FRAGMENT = fp('f');
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void staticSnapshotIsCanonicalDeterministicAndDetached() throws Exception {
        WorldStaticDependencySnapshot.Dependency dependency = dependency(List.of("site-b", "site-a"));
        WorldStaticDependencySnapshot first = staticSnapshot(List.of(dependency), 7,
                Instant.parse("2026-08-27T00:00:00Z"));
        for (int attempt = 0; attempt < 20; attempt++) {
            WorldStaticDependencySnapshot rebuilt = staticSnapshot(
                    new ArrayList<>(List.of(dependency)), 7, Instant.now().plusSeconds(attempt));
            assertThat(rebuilt.fingerprint()).isEqualTo(first.fingerprint());
            assertThat(rebuilt.dependencies()).containsExactly(dependency);
        }
        assertThat(MAPPER.readValue(MAPPER.writeValueAsString(first),
                WorldStaticDependencySnapshot.class)).isEqualTo(first);
        assertThat(MAPPER.writeValueAsString(first)).doesNotContain("secret-canary", "request-payload",
                "response-payload");
        assertThat(first.toString()).doesNotContain("secret-canary", "request-payload", "response-payload");
        assertThatThrownBy(() -> first.dependencies().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void staticSnapshotRejectsTamperingDuplicateSitesAndMalformedFingerprints() {
        WorldStaticDependencySnapshot snapshot = staticSnapshot(List.of(dependency(List.of("site-a"))), 7,
                Instant.parse("2026-08-27T00:00:00Z"));
        assertThatThrownBy(() -> new WorldStaticDependencySnapshot(snapshot.schemaVersion(),
                snapshot.algorithmVersion(), snapshot.tenantId(), snapshot.scenarioId(), snapshot.scenarioRevision(),
                snapshot.scenarioFingerprint(), snapshot.worldModelId(), snapshot.worldRevision(),
                snapshot.worldFingerprint(), snapshot.targetGraphArtifactFingerprint(), snapshot.sourceWatermark() + 1,
                snapshot.generatedAt(), snapshot.dependencies(), snapshot.fingerprint()))
                .isInstanceOf(WorldImpactException.class)
                .hasMessage("RG.WORLD_IMPACT.FINGERPRINT_MISMATCH");
        assertThatThrownBy(() -> dependency(List.of("site-a", "site-a")))
                .isInstanceOf(WorldImpactException.class);
        assertThatThrownBy(() -> WorldStaticDependencySnapshot.create(TENANT, "scenario-a", 1,
                "not-a-fingerprint", "world-a", 1, WORLD, TARGET, 1,
                Instant.parse("2026-08-27T00:00:00Z"), List.of()))
                .isInstanceOf(WorldImpactException.class);
    }

    @Test
    void runtimeSnapshotBindsRunEvidenceAndIsPayloadFree() throws Exception {
        WorldRuntimeConsumptionSnapshot.Consumption consumption = new WorldRuntimeConsumptionSnapshot.Consumption(
                "world-delegate:customer", "world-delegate:customer", "logical.customer", CONTRACT, SLICE,
                FRAGMENT, List.of("/root/lookup#PRIMARY"));
        WorldRuntimeConsumptionSnapshot snapshot = WorldRuntimeConsumptionSnapshot.create(TENANT, "scenario-a", 2,
                SCENARIO, "run-a", fp('1'), TARGET, fp('2'), 9,
                Instant.parse("2026-08-27T00:00:00Z"), Instant.parse("2026-08-27T00:00:01Z"),
                Instant.parse("2026-08-27T00:00:02Z"), List.of(consumption));
        assertThat(MAPPER.readValue(MAPPER.writeValueAsString(snapshot),
                WorldRuntimeConsumptionSnapshot.class)).isEqualTo(snapshot);
        assertThat(snapshot.toString()).doesNotContain("secret-request", "secret-response");
        for (int attempt = 0; attempt < 20; attempt++) {
            WorldRuntimeConsumptionSnapshot rebuilt = WorldRuntimeConsumptionSnapshot.create(TENANT, "scenario-a", 2,
                    SCENARIO, "run-a", fp('1'), TARGET, fp('2'), 9, snapshotStart(),
                    snapshotStart().plusSeconds(1), snapshotStart().plusSeconds(2), List.of(consumption));
            assertThat(rebuilt.fingerprint()).isEqualTo(snapshot.fingerprint());
        }
        assertThatThrownBy(() -> WorldRuntimeConsumptionSnapshot.create(TENANT, "scenario-a", 2,
                SCENARIO, "run-a", "not-a-fingerprint", TARGET, fp('2'), 9,
                snapshotStart(), snapshotStart().plusSeconds(1), snapshotStart(), List.of(consumption)))
                .isInstanceOf(WorldImpactException.class);
    }

    private static WorldStaticDependencySnapshot staticSnapshot(
            List<WorldStaticDependencySnapshot.Dependency> dependencies, long watermark, Instant generatedAt) {
        return WorldStaticDependencySnapshot.create(TENANT, "scenario-a", 1, SCENARIO,
                "world-a", 3, WORLD, TARGET, watermark, generatedAt, dependencies);
    }

    private static WorldStaticDependencySnapshot.Dependency dependency(List<String> sites) {
        return new WorldStaticDependencySnapshot.Dependency("rule-a", "logical.customer", CONTRACT,
                SLICE, FRAGMENT, TARGET, sites);
    }

    private static Instant snapshotStart() {
        return Instant.parse("2026-08-27T00:00:00Z");
    }

    private static String fp(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
