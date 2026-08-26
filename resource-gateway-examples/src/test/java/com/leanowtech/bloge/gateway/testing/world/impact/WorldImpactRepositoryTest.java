package com.leanowtech.bloge.gateway.testing.world.impact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldImpactRepositoryTest {
    private static final String TARGET = fp('a');
    private static final String SCENARIO = fp('b');
    private static final String WORLD = fp('c');

    @Test
    void inMemoryUpsertIsTenantScopedIdempotentAndWatermarked() {
        InMemoryWorldImpactSnapshotRepository repository = new InMemoryWorldImpactSnapshotRepository();
        WorldStaticDependencySnapshot first = staticSnapshot("tenant-a", "scenario-a", 3, 4, 'd');
        WorldStaticDependencySnapshot newer = staticSnapshot("tenant-a", "scenario-b", 1, 9, 'e');

        assertThat(repository.upsertStatic(first).snapshot()).isEqualTo(first);
        assertThat(repository.upsertStatic(first).snapshot()).isEqualTo(first);
        assertThat(repository.upsertStatic(newer).currentWatermark()).isEqualTo(9);
        assertThat(repository.staticSnapshots("tenant-a")).hasSize(2);
        assertThat(repository.staticSnapshots("tenant-a").getFirst().stale()).isTrue();
        assertThat(repository.readStatic("tenant-b", "scenario-a", 3, first.fingerprint()))
                .isEmpty();
        assertThatThrownBy(() -> repository.upsertStatic(staticSnapshot(
                "tenant-a", "scenario-a", 3, 4, 'f')))
                .isInstanceOf(WorldImpactException.class)
                .hasMessage("RG.WORLD_IMPACT.INDEX_CONFLICT");
    }

    @Test
    void concurrentRebuildsPublishOneMonotonicWatermark() throws Exception {
        InMemoryWorldImpactSnapshotRepository repository = new InMemoryWorldImpactSnapshotRepository();
        var first = staticSnapshot("tenant-a", "scenario-a", 1, 10, 'd');
        var second = staticSnapshot("tenant-a", "scenario-b", 1, 11, 'e');
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (var future : pool.invokeAll(List.of(() -> repository.upsertStatic(first),
                    () -> repository.upsertStatic(second)))) {
                future.get();
            }
        }
        assertThat(repository.staticWatermark("tenant-a")).isEqualTo(11);
        assertThat(repository.staticSnapshots("tenant-a")).hasSize(2);
    }

    @Test
    void h2RepositoryRoundTripsAfterNewRepositoryInstanceAndRejectsCrossTenantLookup() throws Exception {
        DataSource dataSource = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).build();
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/h2/V20260827_002__world_impact_indexes.sql")).execute(dataSource);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        WorldStaticDependencySnapshot snapshot = staticSnapshot("tenant-a", "scenario-a", 1, 5, 'd');

        DatabaseWorldImpactSnapshotRepository first = new DatabaseWorldImpactSnapshotRepository(
                jdbc, mapper);
        first.upsertStatic(snapshot);
        WorldRuntimeConsumptionSnapshot runtime = WorldRuntimeConsumptionSnapshot.create("tenant-a", "scenario-a", 1,
                fp('b'), "run-a", fp('1'), TARGET, fp('2'), 6,
                Instant.parse("2026-08-27T00:00:00Z"), Instant.parse("2026-08-27T00:00:01Z"),
                Instant.parse("2026-08-27T00:00:02Z"), List.of(new WorldRuntimeConsumptionSnapshot.Consumption(
                        "rule-a", "rule-a", "logical.customer", fp('3'), fp('4'), fp('5'),
                        List.of("/root/a#PRIMARY"))));
        first.upsertRuntime(runtime);

        DatabaseWorldImpactSnapshotRepository restarted = new DatabaseWorldImpactSnapshotRepository(
                jdbc, mapper);
        Optional<WorldImpactSnapshotRepository.IndexedStatic> restored = restarted.readStatic(
                "tenant-a", "scenario-a", 1, snapshot.fingerprint());
        assertThat(restored).isPresent();
        assertThat(restored.orElseThrow().snapshot()).isEqualTo(snapshot);
        assertThat(restarted.readStatic("tenant-b", "scenario-a", 1, snapshot.fingerprint()))
                .isEmpty();
        assertThat(restarted.staticWatermark("tenant-a")).isEqualTo(5);
        assertThat(restarted.readRuntime("tenant-a", "run-a", runtime.fingerprint()))
                .map(WorldImpactSnapshotRepository.IndexedRuntime::snapshot)
                .contains(runtime);
        assertThat(restarted.runtimeWatermark("tenant-a")).isEqualTo(6);
        String persistedStatic = jdbc.queryForObject(
                "SELECT canonical_json FROM rg_world_impact_static_snapshots WHERE tenant_id=?",
                String.class, "tenant-a");
        assertThat(persistedStatic).doesNotContain("secret-canary", "request-payload", "response-payload");
        dataSource.getConnection().close();
    }

    @Test
    void migrationUsesProductionJsonbAndTestOnlyH2Text() throws Exception {
        String postgres = new ClassPathResource("db/postgresql/V20260827_002__world_impact_indexes.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String h2 = new ClassPathResource("db/h2/V20260827_002__world_impact_indexes.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(postgres).contains("rg_world_impact_static_snapshots",
                "rg_world_impact_runtime_snapshots", "rg_world_impact_watermarks", "JSONB")
                .doesNotContain("CLOB");
        assertThat(h2).contains("rg_world_impact_static_snapshots",
                "rg_world_impact_runtime_snapshots", "rg_world_impact_watermarks", "TEXT");
    }

    private static WorldStaticDependencySnapshot staticSnapshot(String tenant, String scenario,
                                                                 long revision, long watermark, char fragment) {
        WorldStaticDependencySnapshot.Dependency dependency =
                new WorldStaticDependencySnapshot.Dependency("rule-a", "logical.customer", fp('1'),
                        fp('2'), fp(fragment), TARGET, List.of("/root/a#PRIMARY"));
        return WorldStaticDependencySnapshot.create(tenant, scenario, revision, SCENARIO,
                "world-a", 1, WORLD, TARGET, watermark, Instant.parse("2026-08-27T00:00:00Z"),
                List.of(dependency));
    }

    private static String fp(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
