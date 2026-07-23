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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseCapabilityCorpusTrajectoryRepositoryTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final CapabilityCorpusIntegrity integrity =
            new CapabilityCorpusIntegrity(mapper);
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DatabaseCapabilityCorpusTrajectoryRepository repository;
    private CapabilityCorpusTrajectoryPublication publication;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(database);
        repository = new DatabaseCapabilityCorpusTrajectoryRepository(
                jdbc, mapper, integrity);
        repository.init();

        CapabilityObservationRepository.StoredObservation source =
                CapabilityCorpusTestFixtures.admitted(
                        mapper,
                        CapabilityObservationTestFixtures.scope("org-a"),
                        "repository-source");
        CapabilityCorpusRevision revision =
                CapabilityCorpusTestFixtures.revision(
                        mapper,
                        source,
                        "repository-corpus",
                        1,
                        null,
                        source.admission().decidedAt().plusSeconds(1));
        CapabilityCorpusPublication corpusPublication =
                CapabilityCorpusTestFixtures.publication(
                        mapper,
                        revision,
                        1,
                        null,
                        revision.createdAt().plusSeconds(1));
        CapabilityCorpusTrajectoryPublishRequest request =
                new CapabilityCorpusTrajectoryPublishRequest(
                        "",
                        "repository-trajectory",
                        1,
                        null,
                        revision.capabilityRef(),
                        corpusPublication.artifactRef(),
                        CapabilityObservationTestFixtures.ref(
                                "RETRY_POLICY",
                                "repository-retry",
                                1,
                                '7'),
                        List.of(
                                new CapabilityCorpusTrajectoryPublishRequest
                                        .AttemptSource(
                                        1,
                                        source.envelope().artifactRef(),
                                        source.admission().artifactRef()),
                                new CapabilityCorpusTrajectoryPublishRequest
                                        .AttemptSource(
                                        2,
                                        new MirrorArtifactRef(
                                                CapabilityObservationEnvelope
                                                        .ARTIFACT_KIND,
                                                "repository-source-2",
                                                1,
                                                CapabilityObservationTestFixtures
                                                        .fingerprint('8')),
                                        new MirrorArtifactRef(
                                                CapabilityObservationAdmission
                                                        .ARTIFACT_KIND,
                                                "repository-source-2:admission",
                                                1,
                                                CapabilityObservationTestFixtures
                                                        .fingerprint('9')))),
                        CapabilityObservationTestFixtures.ref(
                                "GOVERNANCE_REVIEW_TICKET",
                                "repository-ticket",
                                1,
                                '6'),
                        "OWNER_APPROVED");
        publication =
                CapabilityCorpusTestFixtures.trajectoryPublication(
                        mapper,
                        corpusPublication,
                        revision,
                        request,
                        null,
                        corpusPublication.publishedAt().plusSeconds(1));
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void persistsAcrossRestartAndRecoversExactRetry() {
        repository.append(publication);
        DatabaseCapabilityCorpusTrajectoryRepository restarted =
                new DatabaseCapabilityCorpusTrajectoryRepository(
                        jdbc, mapper, integrity);
        restarted.init();

        assertThat(restarted.find(
                publication.scope(),
                publication.trajectoryId(),
                publication.revision())).contains(publication);
        assertThat(restarted.findLatest(
                publication.scope(),
                publication.trajectoryId())).contains(publication);
        assertThat(restarted.append(publication)).isEqualTo(publication);
    }

    @Test
    void rejectsLineageGapAndDifferentContentAtExactCoordinate() {
        repository.append(publication);
        CapabilityCorpusTrajectoryPublication conflicting =
                integrity.sealTrajectory(
                        new CapabilityCorpusTrajectoryPublication(
                                publication.schemaVersion(),
                                CapabilityObservationTestFixtures
                                        .fingerprint('0'),
                                CapabilityObservationTestFixtures
                                        .fingerprint('a'),
                                publication.scope(),
                                publication.trajectoryId(),
                                publication.revision(),
                                publication.predecessorRef(),
                                publication.capabilityRef(),
                                publication.corpusPublicationRef(),
                                publication.corpusRevisionRef(),
                                publication.publicationPolicyRef(),
                                publication.retryPolicyRef(),
                                publication.requestFingerprint(),
                                publication.attempts(),
                                publication.reviewTicketRef(),
                                publication.reasonCode(),
                                publication.reviewedBy(),
                                publication.publishedAt(),
                                publication.usableUntil()));

        assertReason(
                () -> repository.append(conflicting),
                CapabilityCorpusTrajectoryRepository.Reason.CONTENT_CONFLICT);

        CapabilityCorpusTrajectoryPublication gap = integrity.sealTrajectory(
                new CapabilityCorpusTrajectoryPublication(
                        publication.schemaVersion(),
                        CapabilityObservationTestFixtures.fingerprint('0'),
                        CapabilityObservationTestFixtures.fingerprint('b'),
                        publication.scope(),
                        publication.trajectoryId(),
                        3,
                        new MirrorArtifactRef(
                                CapabilityCorpusTrajectoryPublication
                                        .ARTIFACT_KIND,
                                publication.trajectoryId(),
                                2,
                                CapabilityObservationTestFixtures
                                        .fingerprint('c')),
                        publication.capabilityRef(),
                        publication.corpusPublicationRef(),
                        publication.corpusRevisionRef(),
                        publication.publicationPolicyRef(),
                        publication.retryPolicyRef(),
                        publication.requestFingerprint(),
                        publication.attempts(),
                        publication.reviewTicketRef(),
                        publication.reasonCode(),
                        publication.reviewedBy(),
                        publication.publishedAt().plusSeconds(2),
                        publication.usableUntil()));
        assertReason(
                () -> repository.append(gap),
                CapabilityCorpusTrajectoryRepository.Reason.LINEAGE_CONFLICT);
    }

    @Test
    void detectsTamperedIndexAndStoresNoBusinessPayloadColumns() {
        repository.append(publication);
        jdbc.update("""
                UPDATE mirror_capability_corpus_trajectories
                SET attempt_count = 99
                WHERE trajectory_id = ?
                """, publication.trajectoryId());

        assertReason(
                () -> repository.find(
                        publication.scope(),
                        publication.trajectoryId(),
                        publication.revision()),
                CapabilityCorpusTrajectoryRepository.Reason
                        .STORED_STATE_CORRUPT);

        List<String> columns = jdbc.queryForList("""
                SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = 'MIRROR_CAPABILITY_CORPUS_TRAJECTORIES'
                ORDER BY ORDINAL_POSITION
                """, String.class);
        assertThat(columns).noneMatch(column ->
                column.equals("REQUEST")
                        || column.equals("RESPONSE")
                        || column.contains("RAW_PAYLOAD")
                        || column.contains("SECRET")
                        || column.contains("BUSINESS_KEY"));
        assertThat(columns).contains(
                "TRAJECTORY_FINGERPRINT",
                "COMMAND_FINGERPRINT",
                "TRAJECTORY_JSON");
    }

    private static void assertReason(
            Runnable action,
            CapabilityCorpusTrajectoryRepository.Reason reason) {
        assertThatThrownBy(action::run)
                .isInstanceOf(
                        CapabilityCorpusTrajectoryRepository.Violation.class)
                .extracting(failure ->
                        ((CapabilityCorpusTrajectoryRepository.Violation)
                                failure).reason())
                .isEqualTo(reason);
    }
}
