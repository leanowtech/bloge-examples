package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseCompiledScenarioRehearsalPlanRepositoryTest {
    private static final CapabilitySnapshot.Scope SCOPE =
            new CapabilitySnapshot.Scope(
                    "tenant-a", "org-a", "support", "test", "sg");
    private static final String SHA_A = "sha256:" + "a".repeat(64);
    private static final String SHA_B = "sha256:" + "b".repeat(64);
    private static final String SHA_C = "sha256:" + "c".repeat(64);
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DatabaseCompiledScenarioRehearsalPlanRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(database);
        repository = new DatabaseCompiledScenarioRehearsalPlanRepository(
                jdbc, mapper);
        repository.init();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void persistsACompilerIssuedPlanIdempotentlyAcrossRepositoryInstances() {
        CompiledScenarioRehearsalPlan plan = plan(SHA_A);

        assertThat(repository.create(plan)).isEqualTo(plan);
        assertThat(repository.create(plan)).isEqualTo(plan);
        DatabaseCompiledScenarioRehearsalPlanRepository restarted =
                new DatabaseCompiledScenarioRehearsalPlanRepository(
                        jdbc, mapper);
        restarted.init();

        assertThat(restarted.find(
                SCOPE, plan.planId(), plan.revision())).contains(plan);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM compiled_scenario_rehearsal_plans",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void rejectsConflictingCompilerOutputAndIndexedIdentityTampering() {
        CompiledScenarioRehearsalPlan first = plan(SHA_A);
        CompiledScenarioRehearsalPlan conflict = plan(SHA_B);
        repository.create(first);

        assertThatThrownBy(() -> repository.create(conflict))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different content");

        jdbc.update("""
                UPDATE compiled_scenario_rehearsal_plans
                SET plan_fingerprint = ?
                WHERE plan_id = ?
                """, SHA_C, first.planId());
        assertThatThrownBy(() -> repository.find(
                SCOPE, first.planId(), first.revision()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed integrity validation");
    }

    @Test
    void schemaContainsNoInputFixtureOrLatestPointerColumns() {
        List<String> columns = jdbc.queryForList("""
                SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = 'COMPILED_SCENARIO_REHEARSAL_PLANS'
                ORDER BY ORDINAL_POSITION
                """, String.class);

        assertThat(columns).containsExactly(
                "TENANT_ID",
                "ORGANIZATION_ID",
                "PROJECT_ID",
                "ENVIRONMENT_ID",
                "REGION",
                "PLAN_ID",
                "PLAN_REVISION",
                "PLAN_FINGERPRINT",
                "SCHEMA_VERSION",
                "PLAN_JSON");
        assertThat(columns).noneMatch(column ->
                column.contains("INPUT")
                        || column.contains("FIXTURE_VALUE")
                        || column.contains("PAYLOAD")
                        || column.contains("LATEST"));
    }

    private CompiledScenarioRehearsalPlan plan(String targetFingerprint) {
        MirrorArtifactRef assertion =
                ref("CASE_HANDLING_ASSERTION", "node-status", SHA_A);
        return CompiledScenarioRehearsalPlanIntegrity.seal(
                mapper,
                new CompiledScenarioRehearsalPlan(
                        "",
                        "customer-rehearsal"
                                + ScenarioRehearsalCompiler.PLAN_ID_SUFFIX,
                        1,
                        "",
                        SCOPE,
                        ref("SCENARIO_PACK", "customer-rehearsal", SHA_B),
                        ref("CAPABILITY", "customer-view", targetFingerprint),
                        List.of(
                                new CompiledScenarioRehearsalPlan.CaseBinding(
                                        ref(
                                                "SCENARIO_CASE",
                                                "customer-found",
                                                SHA_C),
                                        ScenarioCase.CaseType.GOLDEN,
                                        ref(
                                                "TEST_SUITE",
                                                "customer-suite",
                                                SHA_A),
                                        "customer-found",
                                        ref(
                                                "MIRROR_PLAN",
                                                "customer-plan",
                                                SHA_B),
                                        ref(
                                                "FIXTURE_BUNDLE",
                                                "customer-fixture",
                                                SHA_C),
                                        null,
                                        new MirrorPlan.ExecutionServices(
                                                Instant.parse(
                                                        "2026-07-24T02:00:00Z"),
                                                42L,
                                                null,
                                                null),
                                        List.of(assertion))),
                        List.of(assertion),
                        new ScenarioPack.RehearsalPolicy(
                                ScenarioPack.Scheduling.SEQUENTIAL,
                                true,
                                false,
                                false,
                                false,
                                ScenarioPack.EvidenceMode.HASH_ONLY,
                                10,
                                100,
                                Duration.ofMinutes(5),
                                Duration.ofMinutes(30),
                                true,
                                CapabilityContract.DataClassification.CONFIDENTIAL,
                                List.of("sg"))));
    }

    private static MirrorArtifactRef ref(
            String kind, String id, String fingerprint) {
        return new MirrorArtifactRef(kind, id, 1, fingerprint);
    }
}
