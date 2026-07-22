package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.List;

import static com.leanowtech.bloge.gateway.integration.mirror.MirrorPersistenceTestFixtures.plan;
import static com.leanowtech.bloge.gateway.integration.mirror.MirrorPersistenceTestFixtures.scope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseMirrorPlanRepositoryTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DatabaseMirrorPlanRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).build();
        jdbc = new JdbcTemplate(database);
        repository = new DatabaseMirrorPlanRepository(jdbc, mapper);
        repository.init();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void persistsVerifiedPlanAcrossRepositoryInstancesAndMakesExactRetryIdempotent() {
        MirrorPlan plan = plan(mapper, scope("org-a"), "plan-a", 'a');

        assertThat(repository.create(plan)).isEqualTo(plan);
        assertThat(repository.create(plan)).isEqualTo(plan);
        DatabaseMirrorPlanRepository restarted = new DatabaseMirrorPlanRepository(jdbc, mapper);
        restarted.init();

        assertThat(restarted.find(plan.scope(), plan.planId())).contains(plan);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mirror_plans", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void isolatesIdenticalPlanIdsByCompleteEnterpriseScope() {
        MirrorPlan orgA = plan(mapper, scope("org-a"), "shared-plan", 'a');
        MirrorPlan orgB = plan(mapper, scope("org-b"), "shared-plan", 'a');

        repository.create(orgA);
        repository.create(orgB);

        assertThat(repository.find(orgA.scope(), orgA.planId())).contains(orgA);
        assertThat(repository.find(orgB.scope(), orgB.planId())).contains(orgB);
        assertThat(repository.find(new CapabilitySnapshot.Scope(
                "tenant-a", "org-a", "other-project", "test", "sg"), orgA.planId()))
                .isEmpty();
    }

    @Test
    void rejectsConflictingIdempotencyIdentityAndInvalidSeal() {
        MirrorPlan first = plan(mapper, scope("org-a"), "plan-a", 'a');
        MirrorPlan conflict = plan(mapper, scope("org-a"), "plan-a", 'c');
        repository.create(first);

        assertThatThrownBy(() -> repository.create(conflict))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Mirror plan id already exists with different content");
        assertThatThrownBy(() -> repository.create(
                first.withFingerprint(MirrorPersistenceTestFixtures.fingerprint('f'))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint mismatch");
    }

    @Test
    void refusesTamperedJsonOrIndexedIdentityInsteadOfServingIt() {
        MirrorPlan value = plan(mapper, scope("org-a"), "plan-a", 'a');
        repository.create(value);
        jdbc.update("UPDATE mirror_plans SET plan_fingerprint = ? WHERE plan_id = ?",
                MirrorPersistenceTestFixtures.fingerprint('f'), value.planId());

        assertThatThrownBy(() -> repository.find(value.scope(), value.planId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Stored mirror plan failed integrity validation");

        jdbc.update("UPDATE mirror_plans SET plan_json = '{}' WHERE plan_id = ?", value.planId());
        assertThatThrownBy(() -> repository.find(value.scope(), value.planId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Stored mirror plan failed integrity validation");
    }

    @Test
    void schemaHasNoBusinessPayloadOrReplayColumns() {
        List<String> columns = jdbc.queryForList("""
                SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = 'MIRROR_PLANS'
                ORDER BY ORDINAL_POSITION
                """, String.class);

        assertThat(columns).containsExactly("TENANT_ID", "ORGANIZATION_ID", "PROJECT_ID",
                "ENVIRONMENT_ID", "REGION", "PLAN_ID", "PLAN_FINGERPRINT", "SCHEMA_VERSION",
                "COMPILED_AT", "EXPIRES_AT", "PLAN_JSON");
        assertThat(columns).noneMatch(column -> column.contains("PAYLOAD")
                || column.contains("CONTEXT") || column.contains("RESULT"));
    }
}
