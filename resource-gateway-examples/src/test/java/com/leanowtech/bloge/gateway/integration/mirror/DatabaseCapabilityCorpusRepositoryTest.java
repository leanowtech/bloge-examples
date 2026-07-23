package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseCapabilityCorpusRepositoryTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final CapabilityCorpusIntegrity integrity =
            new CapabilityCorpusIntegrity(mapper);
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DatabaseCapabilityCorpusRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(database);
        repository = new DatabaseCapabilityCorpusRepository(
                jdbc, mapper, integrity);
        repository.init();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void persistsIndependentCandidateAndPublicationLineagesAcrossRestart() {
        CapabilityCorpusRevision revision1 = revision(
                "org-a", "support-corpus", 1, null);
        CapabilityCorpusRevision revision2 = revision(
                "org-a", "support-corpus", 2, revision1.artifactRef());
        CapabilityCorpusPublication publication1 =
                CapabilityCorpusTestFixtures.publication(
                        mapper,
                        revision1,
                        1,
                        null,
                        revision1.createdAt().plusSeconds(1));

        repository.appendRevision(revision1);
        repository.appendPublication(publication1);
        repository.appendRevision(revision2);
        DatabaseCapabilityCorpusRepository restarted =
                new DatabaseCapabilityCorpusRepository(jdbc, mapper, integrity);
        restarted.init();

        assertThat(restarted.findLatestRevision(
                revision1.scope(), revision1.corpusId())).contains(revision2);
        assertThat(restarted.findLatestPublication(
                revision1.scope(), revision1.corpusId()))
                .contains(publication1);
        assertThat(restarted.appendRevision(revision2)).isEqualTo(revision2);
        assertThat(restarted.appendPublication(publication1))
                .isEqualTo(publication1);
    }

    @Test
    void rejectsGapForkAndDifferentContentAtExactCoordinates() {
        CapabilityCorpusRevision revision1 = revision(
                "org-a", "support-corpus", 1, null);
        repository.appendRevision(revision1);
        CapabilityCorpusRevision gap = integrity.sealRevision(
                new CapabilityCorpusRevision(
                        "",
                        CapabilityObservationTestFixtures.fingerprint('0'),
                        CapabilityObservationTestFixtures.fingerprint('8'),
                        revision1.scope(),
                        revision1.corpusId(),
                        3,
                        new MirrorArtifactRef(
                                CapabilityCorpusRevision.ARTIFACT_KIND,
                                revision1.corpusId(),
                                2,
                                CapabilityObservationTestFixtures.fingerprint('7')),
                        revision1.capabilityRef(),
                        revision1.governancePolicyRef(),
                        revision1.sources(),
                        revision1.riskSummary(),
                        revision1.createdBy(),
                        revision1.createdAt().plusSeconds(2),
                        revision1.usableUntil()));

        assertReason(
                () -> repository.appendRevision(gap),
                CapabilityCorpusRepository.Reason.LINEAGE_CONFLICT);

        CapabilityCorpusRevision conflicting = integrity.sealRevision(
                new CapabilityCorpusRevision(
                        "",
                        CapabilityObservationTestFixtures.fingerprint('0'),
                        CapabilityObservationTestFixtures.fingerprint('9'),
                        revision1.scope(),
                        revision1.corpusId(),
                        revision1.revision(),
                        revision1.predecessorRef(),
                        revision1.capabilityRef(),
                        revision1.governancePolicyRef(),
                        revision1.sources(),
                        revision1.riskSummary(),
                        revision1.createdBy(),
                        revision1.createdAt(),
                        revision1.usableUntil()));
        assertReason(
                () -> repository.appendRevision(conflicting),
                CapabilityCorpusRepository.Reason.CONTENT_CONFLICT);
    }

    @Test
    void isolatesSameCorpusIdByCompleteEnterpriseScope() {
        CapabilityCorpusRevision orgA = revision(
                "org-a", "shared-corpus", 1, null);
        CapabilityCorpusRevision orgB = revision(
                "org-b", "shared-corpus", 1, null);

        repository.appendRevision(orgA);
        repository.appendRevision(orgB);

        assertThat(repository.findRevision(
                orgA.scope(), "shared-corpus", 1)).contains(orgA);
        assertThat(repository.findRevision(
                orgB.scope(), "shared-corpus", 1)).contains(orgB);
    }

    @Test
    void detectsTamperedRevisionAndPublicationIndexes() {
        CapabilityCorpusRevision revision = revision(
                "org-a", "tamper-corpus", 1, null);
        CapabilityCorpusPublication publication =
                CapabilityCorpusTestFixtures.publication(
                        mapper,
                        revision,
                        1,
                        null,
                        revision.createdAt().plusSeconds(1));
        repository.appendRevision(revision);
        repository.appendPublication(publication);

        jdbc.update("""
                UPDATE mirror_capability_corpus_revisions
                SET sample_count = 999
                WHERE corpus_id = ?
                """, revision.corpusId());
        assertReason(
                () -> repository.findRevision(
                        revision.scope(), revision.corpusId(), 1),
                CapabilityCorpusRepository.Reason.STORED_STATE_CORRUPT);

        jdbc.update("""
                UPDATE mirror_capability_corpus_publications
                SET reviewed_by = 'different-reviewer'
                WHERE corpus_id = ?
                """, publication.corpusId());
        assertReason(
                () -> repository.findPublication(
                        publication.scope(), publication.corpusId(), 1),
                CapabilityCorpusRepository.Reason.STORED_STATE_CORRUPT);
    }

    @Test
    void tablesCannotRepresentBusinessPayloadBytes() {
        var columns = jdbc.queryForList("""
                SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME IN (
                    'MIRROR_CAPABILITY_CORPUS_REVISIONS',
                    'MIRROR_CAPABILITY_CORPUS_PUBLICATIONS'
                )
                ORDER BY TABLE_NAME, ORDINAL_POSITION
                """, String.class);

        assertThat(columns).noneMatch(column ->
                column.equals("REQUEST")
                        || column.equals("RESPONSE")
                        || column.contains("RAW_PAYLOAD")
                        || column.contains("SECRET")
                        || column.contains("BUSINESS_KEY"));
        assertThat(columns).contains(
                "REVISION_FINGERPRINT",
                "PUBLICATION_FINGERPRINT",
                "REVISION_JSON",
                "PUBLICATION_JSON");
    }

    private CapabilityCorpusRevision revision(
            String organization,
            String corpusId,
            long revision,
            MirrorArtifactRef predecessor) {
        CapabilityObservationRepository.StoredObservation source =
                CapabilityCorpusTestFixtures.admitted(
                        mapper,
                        CapabilityObservationTestFixtures.scope(organization),
                        "observation-" + organization);
        return CapabilityCorpusTestFixtures.revision(
                mapper,
                source,
                corpusId,
                revision,
                predecessor,
                source.admission().decidedAt().plusSeconds(revision));
    }

    private static void assertReason(
            Runnable action, CapabilityCorpusRepository.Reason reason) {
        assertThatThrownBy(action::run)
                .isInstanceOf(CapabilityCorpusRepository.Violation.class)
                .extracting(failure ->
                        ((CapabilityCorpusRepository.Violation) failure).reason())
                .isEqualTo(reason);
    }
}
