package com.leanowtech.bloge.gateway.visual.authoring.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestEvidenceProtocol.AssetKind;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestEvidenceProtocol.CaseSummary;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestEvidenceProtocol.Coverage;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestEvidenceProtocol.EvidenceRecord;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseAuthoringTestEvidenceRepositoryTest {

    private static final AuthoringTestScope SCOPE =
            new AuthoringTestScope("tenant-a", "org-a", "project-a", "test", "sg");

    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DatabaseAuthoringTestEvidenceRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .build();
        jdbc = new JdbcTemplate(database);
        repository = new DatabaseAuthoringTestEvidenceRepository(
                jdbc,
                new ObjectMapper().findAndRegisterModules(),
                new InMemoryVisualEvidenceSigner());
        repository.init();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void persistsAnImmutableSignedPayloadFreeRecord() {
        EvidenceRecord stored = repository.create(record("run-1", SCOPE));

        assertThat(stored.materialFingerprint()).startsWith("sha256:");
        assertThat(stored.seal().signed()).isTrue();
        assertThat(repository.find(SCOPE, stored.runId())).contains(stored);
        assertThat(repository.findByDraft(SCOPE, stored.draftId()))
                .containsExactly(stored);
        String serialized = jdbc.queryForObject(
                "SELECT record_json FROM visual_library_authoring_test_evidence",
                String.class);
        assertThat(serialized)
                .contains("\"payloadPersisted\":false")
                .doesNotContain("customer-secret-input")
                .doesNotContain("\"inputs\"")
                .doesNotContain("\"outputs\"");
    }

    @Test
    void isolatesAllFiveEnterpriseScopeDimensions() {
        repository.create(record("run-scope", SCOPE));

        assertThat(repository.find(
                new AuthoringTestScope(
                        "tenant-a", "org-a", "other-project", "test", "sg"),
                "run-scope")).isEmpty();
        assertThat(repository.findByDraft(
                new AuthoringTestScope(
                        "tenant-a", "org-a", "project-a", "test", "us"),
                "draft-a")).isEmpty();
    }

    @Test
    void rejectsProjectedColumnTamperingBeforeReturningEvidence() {
        repository.create(record("run-tampered", SCOPE));
        jdbc.update("""
                UPDATE visual_library_authoring_test_evidence
                SET asset_ref = 'demo:substituted'
                WHERE run_id = 'run-tampered'
                """);

        assertThatThrownBy(() -> repository.find(SCOPE, "run-tampered"))
                .isInstanceOf(AuthoringTestEvidenceIntegrityException.class);
    }

    @Test
    void rejectsSignedBodyTamperingBeforeReturningEvidence() {
        repository.create(record("run-body-tampered", SCOPE));
        String json = jdbc.queryForObject("""
                SELECT record_json
                FROM visual_library_authoring_test_evidence
                WHERE run_id = 'run-body-tampered'
                """, String.class);
        jdbc.update("""
                UPDATE visual_library_authoring_test_evidence
                SET record_json = ?
                WHERE run_id = 'run-body-tampered'
                """, json.replace("\"passed\":true", "\"passed\":false"));

        assertThatThrownBy(() -> repository.find(SCOPE, "run-body-tampered"))
                .isInstanceOf(AuthoringTestEvidenceIntegrityException.class);
    }

    @Test
    void neverOverwritesAnExistingScopedRunIdentity() {
        repository.create(record("run-duplicate", SCOPE));

        assertThatThrownBy(() -> repository.create(record("run-duplicate", SCOPE)))
                .isInstanceOf(AuthoringTestEvidenceIntegrityException.class);
    }

    private static EvidenceRecord record(
            String runId,
            AuthoringTestScope scope) {
        String fingerprint = "sha256:" + "a".repeat(64);
        return new EvidenceRecord(
                EvidenceRecord.SCHEMA_VERSION,
                scope,
                runId,
                AssetKind.OPERATOR,
                "demo:echo",
                "draft-a",
                7,
                fingerprint,
                fingerprint,
                fingerprint,
                "",
                "",
                fingerprint,
                fingerprint,
                AuthoringTestEvidenceProtocol.POLICY_VERSION,
                "SCHEMA_CONTRACT",
                "",
                true,
                1,
                1,
                0,
                1,
                new Coverage(1, 1, 1, 1, 1),
                List.of(new CaseSummary(
                        "golden",
                        "CONTRACT",
                        "PASSED",
                        true,
                        1,
                        12,
                        "",
                        List.of())),
                List.of("suite://operator/demo:echo/golden"),
                List.of(),
                Instant.parse("2026-07-31T00:00:00.123456789Z"),
                "quality-bot",
                false,
                "",
                null);
    }
}
