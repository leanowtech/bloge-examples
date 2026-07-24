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
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseScenarioRehearsalEvidenceRepositoryTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final VisualEvidenceSigner signer =
            new InMemoryVisualEvidenceSigner();
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DatabaseScenarioRehearsalEvidenceRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(database);
        repository = repository(signer);
        repository.init();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void persistsReverifiesAndIdempotentlyRecoversSignedEvidence() {
        CapabilitySnapshot.Scope scope =
                MirrorPersistenceTestFixtures.scope("org-a");
        ScenarioRehearsalEvidenceBundle bundle =
                bundle(scope, '5');

        assertThat(repository.create(bundle)).isEqualTo(bundle);
        assertThat(repository.create(bundle)).isEqualTo(bundle);
        DatabaseScenarioRehearsalEvidenceRepository restarted =
                repository(signer);
        restarted.init();

        assertThat(restarted.find(
                scope,
                bundle.attestation().runId()))
                .contains(bundle);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM scenario_rehearsal_evidence",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void isolatesTheSameRunIdentityByCompleteEnterpriseScope() {
        CapabilitySnapshot.Scope orgA =
                MirrorPersistenceTestFixtures.scope("org-a");
        CapabilitySnapshot.Scope orgB =
                MirrorPersistenceTestFixtures.scope("org-b");
        ScenarioRehearsalEvidenceBundle first =
                bundle(orgA, '5');
        ScenarioRehearsalEvidenceBundle second =
                bundle(orgB, '5');

        repository.create(first);
        repository.create(second);

        assertThat(repository.find(
                orgA, first.attestation().runId()))
                .contains(first);
        assertThat(repository.find(
                orgB, second.attestation().runId()))
                .contains(second);
    }

    @Test
    void rejectsConflictingRunAndTamperedIndexOrVerificationKey() {
        CapabilitySnapshot.Scope scope =
                MirrorPersistenceTestFixtures.scope("org-a");
        ScenarioRehearsalEvidenceBundle first =
                bundle(scope, '5');
        ScenarioRehearsalEvidenceBundle conflict =
                bundle(scope, '7');
        repository.create(first);

        assertThatThrownBy(() -> repository.create(conflict))
                .isInstanceOf(
                        ScenarioRehearsalEvidenceStoreException.class)
                .satisfies(failure -> assertThat(
                        ((ScenarioRehearsalEvidenceStoreException) failure)
                                .reason())
                        .isEqualTo(
                                ScenarioRehearsalEvidenceStoreException
                                        .Reason.CONFLICT));

        jdbc.update(
                "UPDATE scenario_rehearsal_evidence "
                        + "SET compiled_plan_id = ? WHERE run_id = ?",
                "tampered-plan",
                first.attestation().runId());
        assertThatThrownBy(() -> repository.find(
                scope,
                first.attestation().runId()))
                .isInstanceOf(
                        ScenarioRehearsalEvidenceStoreException.class)
                .satisfies(failure -> assertThat(
                        ((ScenarioRehearsalEvidenceStoreException) failure)
                                .reason())
                        .isEqualTo(
                                ScenarioRehearsalEvidenceStoreException
                                        .Reason.INTEGRITY_INVALID));

        jdbc.update(
                "UPDATE scenario_rehearsal_evidence "
                        + "SET compiled_plan_id = ?, completed_at = ? "
                        + "WHERE run_id = ?",
                first.result().compiledPlanRef().id(),
                first.result().completedAt().plusSeconds(1).toString(),
                first.attestation().runId());
        assertThatThrownBy(() -> repository.find(
                scope,
                first.attestation().runId()))
                .isInstanceOf(
                        ScenarioRehearsalEvidenceStoreException.class)
                .satisfies(failure -> assertThat(
                        ((ScenarioRehearsalEvidenceStoreException) failure)
                                .reason())
                        .isEqualTo(
                                ScenarioRehearsalEvidenceStoreException
                                        .Reason.INTEGRITY_INVALID));

        DatabaseScenarioRehearsalEvidenceRepository unknownKey =
                repository(new InMemoryVisualEvidenceSigner());
        assertThatThrownBy(() -> unknownKey.create(first))
                .isInstanceOf(
                        ScenarioRehearsalEvidenceStoreException.class)
                .satisfies(failure -> assertThat(
                        ((ScenarioRehearsalEvidenceStoreException) failure)
                                .reason())
                        .isEqualTo(
                                ScenarioRehearsalEvidenceStoreException
                                        .Reason.INTEGRITY_INVALID));

        DatabaseScenarioRehearsalEvidenceRepository noAuthority =
                repository(VisualEvidenceSigner.unavailable());
        assertThatThrownBy(() -> noAuthority.create(first))
                .isInstanceOf(
                        ScenarioRehearsalEvidenceStoreException.class)
                .satisfies(failure -> assertThat(
                        ((ScenarioRehearsalEvidenceStoreException) failure)
                                .reason())
                        .isEqualTo(
                                ScenarioRehearsalEvidenceStoreException
                                        .Reason.VERIFICATION_UNAVAILABLE));
    }

    @Test
    void schemaCannotStoreBusinessPayloadColumns() {
        List<String> columns = jdbc.queryForList("""
                SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = 'SCENARIO_REHEARSAL_EVIDENCE'
                ORDER BY ORDINAL_POSITION
                """, String.class);

        assertThat(columns).containsExactly(
                "TENANT_ID", "ORGANIZATION_ID", "PROJECT_ID",
                "ENVIRONMENT_ID", "REGION", "RUN_ID", "REQUEST_ID",
                "COMPILED_PLAN_ID", "COMPILED_PLAN_REVISION",
                "COMPILED_PLAN_FINGERPRINT", "RESULT_FINGERPRINT",
                "BUNDLE_FINGERPRINT", "SCHEMA_VERSION", "COMPLETED_AT",
                "EVIDENCE_JSON");
        assertThat(columns).noneMatch(column ->
                column.contains("PAYLOAD")
                        || column.contains("FIXTURE")
                        || column.contains("CONTEXT")
                        || column.contains("ENTITY"));
    }

    private ScenarioRehearsalEvidenceBundle bundle(
            CapabilitySnapshot.Scope scope, char planFingerprint) {
        ScenarioRehearsalResult result =
                ScenarioRehearsalEvidenceTestFixtures.result(
                        mapper, scope, planFingerprint);
        return integrity(signer)
                .seal(
                        ScenarioRehearsalRunIdentity.derive(
                                mapper,
                                scope,
                                ScenarioRehearsalEvidenceTestFixtures
                                        .REQUEST_ID),
                        result)
                .bundle();
    }

    private DatabaseScenarioRehearsalEvidenceRepository repository(
            VisualEvidenceSigner verifier) {
        return new DatabaseScenarioRehearsalEvidenceRepository(
                jdbc, mapper, integrity(verifier));
    }

    private ScenarioRehearsalEvidenceIntegrityService integrity(
            VisualEvidenceSigner verifier) {
        return new ScenarioRehearsalEvidenceIntegrityService(
                mapper,
                verifier,
                Clock.fixed(
                        ScenarioRehearsalEvidenceTestFixtures.COMPLETED
                                .plusSeconds(5),
                        ZoneOffset.UTC));
    }
}
