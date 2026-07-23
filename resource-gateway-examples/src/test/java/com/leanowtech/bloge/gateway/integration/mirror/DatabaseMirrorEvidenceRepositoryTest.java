package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Clock;
import java.util.List;

import static com.leanowtech.bloge.gateway.integration.mirror.MirrorPersistenceTestFixtures.evidence;
import static com.leanowtech.bloge.gateway.integration.mirror.MirrorPersistenceTestFixtures.plan;
import static com.leanowtech.bloge.gateway.integration.mirror.MirrorPersistenceTestFixtures.scope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseMirrorEvidenceRepositoryTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final VisualEvidenceSigner signer = new InMemoryVisualEvidenceSigner();
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DatabaseMirrorEvidenceRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).build();
        jdbc = new JdbcTemplate(database);
        repository = repository(signer);
        repository.init();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void persistsVerifiedEvidenceAcrossRepositoryInstancesAndMakesRetryIdempotent() {
        MirrorPlan plan = plan(mapper, scope("org-a"), "plan-a", 'a');
        MirrorEvidenceBundle bundle = evidence(mapper, signer, plan, "run-a", 'c');

        assertThat(repository.create(bundle)).isEqualTo(bundle);
        assertThat(repository.create(bundle)).isEqualTo(bundle);
        DatabaseMirrorEvidenceRepository restarted = repository(signer);
        restarted.init();

        assertThat(restarted.find(plan.scope(), "run-a")).contains(bundle);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM mirror_run_evidence", Integer.class)).isEqualTo(1);
    }

    @Test
    void persistsAndReverifiesStatefulV3EvidenceAcrossRepositoryInstances() {
        MirrorPlan plan = plan(
                mapper, scope("org-a"), "plan-state", 'a');
        MirrorEvidenceBundle bundle =
                MirrorPersistenceTestFixtures.statefulEvidence(
                        mapper, signer, plan, "run-state", 'c');

        repository.create(bundle);
        DatabaseMirrorEvidenceRepository restarted =
                repository(signer);
        restarted.init();

        assertThat(restarted.find(
                plan.scope(), "run-state")).contains(bundle);
        MirrorStateWorkbookSeed seed =
                MirrorStateWorkbookSeed.project(
                        mapper, restarted.find(
                                plan.scope(), "run-state")
                                .orElseThrow());
        seed.verify(mapper);
        assertThat(seed.stateEvidenceRef().fingerprint())
                .isEqualTo(bundle.evidence().stateEvidence()
                        .stateEvidenceFingerprint());
        assertThat(jdbc.queryForObject(
                "SELECT schema_version FROM mirror_run_evidence "
                        + "WHERE run_id = ?",
                String.class, "run-state"))
                .isEqualTo(
                        MirrorEvidenceBundle
                                .STATEFUL_SCHEMA_VERSION);
    }

    @Test
    void isolatesIdenticalRunIdsByCompleteEnterpriseScope() {
        MirrorPlan orgA = plan(mapper, scope("org-a"), "plan-a", 'a');
        MirrorPlan orgB = plan(mapper, scope("org-b"), "plan-b", 'a');
        MirrorEvidenceBundle bundleA = evidence(mapper, signer, orgA, "shared-run", 'c');
        MirrorEvidenceBundle bundleB = evidence(mapper, signer, orgB, "shared-run", 'c');

        repository.create(bundleA);
        repository.create(bundleB);

        assertThat(repository.find(orgA.scope(), "shared-run")).contains(bundleA);
        assertThat(repository.find(orgB.scope(), "shared-run")).contains(bundleB);
        assertThat(repository.find(new CapabilitySnapshot.Scope(
                "tenant-a", "org-a", "other-project", "test", "sg"), "shared-run"))
                .isEmpty();
    }

    @Test
    void rejectsConflictingRunIdentityAndUnavailableSignatureAuthority() {
        MirrorPlan plan = plan(mapper, scope("org-a"), "plan-a", 'a');
        MirrorEvidenceBundle first = evidence(mapper, signer, plan, "run-a", 'c');
        MirrorEvidenceBundle conflict = evidence(mapper, signer, plan, "run-a", 'd');
        repository.create(first);

        assertThatThrownBy(() -> repository.create(conflict))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Mirror run id already exists with different evidence");
        DatabaseMirrorEvidenceRepository unavailable = repository(
                VisualEvidenceSigner.unavailable());
        assertThatThrownBy(() -> unavailable.create(first))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNAVAILABLE");
    }

    @Test
    void refusesTamperedSignedJsonIndexedIdentityOrUnknownVerificationKey() {
        MirrorPlan plan = plan(mapper, scope("org-a"), "plan-a", 'a');
        MirrorEvidenceBundle bundle = evidence(mapper, signer, plan, "run-a", 'c');
        repository.create(bundle);
        String originalJson = jdbc.queryForObject(
                "SELECT evidence_json FROM mirror_run_evidence WHERE run_id = ?",
                String.class, "run-a");
        jdbc.update("UPDATE mirror_run_evidence SET plan_id = ? WHERE run_id = ?",
                "other-plan", "run-a");

        assertThatThrownBy(() -> repository.find(plan.scope(), "run-a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Stored mirror evidence failed integrity validation");

        jdbc.update("UPDATE mirror_run_evidence SET evidence_json = '{}' WHERE run_id = ?",
                "run-a");
        assertThatThrownBy(() -> repository.find(plan.scope(), "run-a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Stored mirror evidence failed integrity validation");

        jdbc.update("""
                UPDATE mirror_run_evidence SET evidence_json = ?, plan_id = ? WHERE run_id = ?
                """, originalJson, plan.planId(), "run-a");
        DatabaseMirrorEvidenceRepository unknownKey = repository(
                new InMemoryVisualEvidenceSigner());
        assertThatThrownBy(() -> unknownKey.find(plan.scope(), "run-a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Stored mirror evidence failed integrity validation");
    }

    @Test
    void schemaHasNoBusinessPayloadFixtureReplayOrResultColumns() {
        List<String> columns = jdbc.queryForList("""
                SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = 'MIRROR_RUN_EVIDENCE'
                ORDER BY ORDINAL_POSITION
                """, String.class);

        assertThat(columns).containsExactly("TENANT_ID", "ORGANIZATION_ID", "PROJECT_ID",
                "ENVIRONMENT_ID", "REGION", "RUN_ID", "PLAN_ID", "PLAN_FINGERPRINT",
                "BUNDLE_FINGERPRINT", "SCHEMA_VERSION", "COMPLETED_AT", "EVIDENCE_JSON");
        assertThat(columns).noneMatch(column -> column.contains("PAYLOAD")
                || column.contains("FIXTURE") || column.contains("REPLAY")
                || column.contains("CONTEXT") || column.contains("RESULT"));
    }

    private DatabaseMirrorEvidenceRepository repository(VisualEvidenceSigner verifier) {
        return new DatabaseMirrorEvidenceRepository(jdbc, mapper,
                new MirrorEvidenceIntegrityService(mapper, verifier, Clock.systemUTC()));
    }
}
