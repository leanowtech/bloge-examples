package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Clock;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseScenarioRehearsalBatchEvidenceRepositoryTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final InMemoryVisualEvidenceSigner signer =
            new InMemoryVisualEvidenceSigner();
    private EmbeddedDatabase database;
    private ScenarioRehearsalBatchEvidenceIntegrityService
            integrity;
    private DatabaseScenarioRehearsalBatchEvidenceRepository
            repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        integrity =
                new ScenarioRehearsalBatchEvidenceIntegrityService(
                        mapper,
                        signer,
                        Clock.fixed(
                                ScenarioRehearsalBatchEvidenceTestFixtures
                                        .COMPLETED.plusSeconds(1),
                                ZoneOffset.UTC));
        repository =
                new DatabaseScenarioRehearsalBatchEvidenceRepository(
                        new JdbcTemplate(database),
                        mapper,
                        integrity);
        repository.init();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void persistsIdempotentlyAndReverifiesAfterRestart() {
        ScenarioRehearsalBatchEvidenceBundle bundle = bundle();

        ScenarioRehearsalBatchEvidenceBundle created =
                repository.create(bundle);
        ScenarioRehearsalBatchEvidenceBundle replay =
                repository.create(bundle);
        DatabaseScenarioRehearsalBatchEvidenceRepository restarted =
                new DatabaseScenarioRehearsalBatchEvidenceRepository(
                        new JdbcTemplate(database),
                        mapper,
                        integrity);
        restarted.init();

        assertThat(created).isEqualTo(bundle);
        assertThat(replay).isEqualTo(bundle);
        assertThat(restarted.find(
                ScenarioRehearsalBatchEvidenceTestFixtures.SCOPE,
                bundle.attestation().jobId()))
                .contains(bundle);
        CapabilitySnapshot.Scope other =
                new CapabilitySnapshot.Scope(
                        "tenant-a",
                        "org-b",
                        "support",
                        "test",
                        "sg");
        assertThat(restarted.find(
                other, bundle.attestation().jobId()))
                .isEmpty();
    }

    @Test
    void rejectsStoredIndexColumnDriftBeforeReturningEvidence() {
        ScenarioRehearsalBatchEvidenceBundle bundle = bundle();
        repository.create(bundle);
        new JdbcTemplate(database).update("""
                UPDATE scenario_rehearsal_batch_evidence
                SET index_fingerprint = ?
                WHERE job_id = ?
                """,
                ScenarioRehearsalBatchEvidenceTestFixtures
                        .fingerprint('9'),
                bundle.attestation().jobId());

        assertThatThrownBy(() -> repository.find(
                ScenarioRehearsalBatchEvidenceTestFixtures.SCOPE,
                bundle.attestation().jobId()))
                .isInstanceOf(
                        ScenarioRehearsalBatchEvidenceStoreException
                                .class);
    }

    private ScenarioRehearsalBatchEvidenceBundle bundle() {
        ScenarioRehearsalBatchEvidenceTestFixtures.Material
                material =
                ScenarioRehearsalBatchEvidenceTestFixtures.material(
                        mapper);
        return integrity.seal(
                material.request(),
                material.manifest(),
                material.job(),
                material.items()).bundle();
    }
}
