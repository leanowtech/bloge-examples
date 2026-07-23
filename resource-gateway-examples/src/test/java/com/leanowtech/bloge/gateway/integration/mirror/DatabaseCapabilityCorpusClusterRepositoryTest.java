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

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseCapabilityCorpusClusterRepositoryTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final CapabilityCorpusIntegrity integrity =
            new CapabilityCorpusIntegrity(mapper);
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DatabaseCapabilityCorpusClusterRepository repository;
    private CapabilityCorpusClusterPublication publication;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(database);
        repository = new DatabaseCapabilityCorpusClusterRepository(
                jdbc, mapper, integrity);
        repository.init();

        CapabilitySnapshot.Scope scope =
                CapabilityObservationTestFixtures.scope("org-a");
        CapabilitySnapshot capability =
                CapabilityObservationTestFixtures.capability(mapper, scope);
        InMemoryVisualEvidenceSigner signer = new InMemoryVisualEvidenceSigner();
        Instant occurredAt = Instant.now().minusSeconds(5);
        List<CapabilityObservationRepository.StoredObservation> sources =
                List.of(
                        CapabilityCorpusTestFixtures.clusterObservation(
                                mapper, signer, capability,
                                "repo-cluster-001", occurredAt, true),
                        CapabilityCorpusTestFixtures.clusterObservation(
                                mapper, signer, capability,
                                "repo-cluster-002",
                                occurredAt.plusSeconds(1), true),
                        CapabilityCorpusTestFixtures.clusterObservation(
                                mapper, signer, capability,
                                "repo-cluster-003",
                                occurredAt.plusSeconds(2), true));
        CapabilityCorpusRevision revision =
                CapabilityCorpusTestFixtures.revision(
                        mapper,
                        sources,
                        "repo-cluster-corpus",
                        occurredAt.plus(Duration.ofHours(1)));
        CapabilityCorpusPublication corpusPublication =
                CapabilityCorpusTestFixtures.publication(
                        mapper,
                        revision,
                        1,
                        null,
                        revision.createdAt().plusSeconds(1));
        CapabilityCorpusClusterValidation validation =
                CapabilityCorpusTestFixtures.clusterValidation(
                        mapper,
                        corpusPublication,
                        revision,
                        sources,
                        corpusPublication.publishedAt().plusSeconds(1));
        CapabilityCorpusClusterPublishRequest request =
                CapabilityCorpusTestFixtures.clusterRequest(
                        corpusPublication, validation);
        publication = CapabilityCorpusTestFixtures.clusterPublication(
                mapper,
                corpusPublication,
                revision,
                validation,
                request,
                null,
                validation.validatedAt().plusSeconds(1));
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void persistsAcrossRestartAndRecoversExactRetry() {
        repository.append(publication);
        DatabaseCapabilityCorpusClusterRepository restarted =
                new DatabaseCapabilityCorpusClusterRepository(
                        jdbc, mapper, integrity);
        restarted.init();

        assertThat(restarted.find(
                publication.scope(),
                publication.clusterId(),
                publication.revision())).contains(publication);
        assertThat(restarted.findLatest(
                publication.scope(),
                publication.clusterId())).contains(publication);
        assertThat(restarted.append(publication)).isEqualTo(publication);
    }

    @Test
    void rejectsLineageGapAndDifferentContentAtExactCoordinate() {
        repository.append(publication);
        CapabilityCorpusClusterPublication conflicting =
                integrity.sealCluster(copy(
                        publication,
                        publication.revision(),
                        publication.predecessorRef(),
                        CapabilityObservationTestFixtures.fingerprint('d'),
                        "DIFFERENT_REASON",
                        publication.publishedAt()));
        assertReason(
                () -> repository.append(conflicting),
                CapabilityCorpusClusterRepository.Reason.CONTENT_CONFLICT);

        CapabilityCorpusClusterPublication gap = integrity.sealCluster(copy(
                publication,
                3,
                new MirrorArtifactRef(
                        CapabilityCorpusClusterPublication.ARTIFACT_KIND,
                        publication.clusterId(),
                        2,
                        CapabilityObservationTestFixtures.fingerprint('e')),
                CapabilityObservationTestFixtures.fingerprint('f'),
                publication.reasonCode(),
                publication.publishedAt().plusSeconds(2)));
        assertReason(
                () -> repository.append(gap),
                CapabilityCorpusClusterRepository.Reason.LINEAGE_CONFLICT);
    }

    @Test
    void detectsTamperedConfidenceIndexAndStoresNoBusinessPayloadColumns() {
        repository.append(publication);
        jdbc.update("""
                UPDATE mirror_capability_corpus_clusters
                SET confidence_lower_bound = 0.0
                WHERE cluster_id = ?
                """, publication.clusterId());

        assertReason(
                () -> repository.find(
                        publication.scope(),
                        publication.clusterId(),
                        publication.revision()),
                CapabilityCorpusClusterRepository.Reason.STORED_STATE_CORRUPT);

        List<String> columns = jdbc.queryForList("""
                SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = 'MIRROR_CAPABILITY_CORPUS_CLUSTERS'
                ORDER BY ORDINAL_POSITION
                """, String.class);
        assertThat(columns).noneMatch(column ->
                column.equals("REQUEST")
                        || column.equals("RESPONSE")
                        || column.contains("RAW_PAYLOAD")
                        || column.contains("SECRET")
                        || column.contains("BUSINESS_KEY"));
        assertThat(columns).contains(
                "CLUSTER_FINGERPRINT",
                "VALIDATION_FINGERPRINT",
                "CONFIDENCE_LOWER_BOUND",
                "CLUSTER_JSON");
    }

    private static CapabilityCorpusClusterPublication copy(
            CapabilityCorpusClusterPublication value,
            long revision,
            MirrorArtifactRef predecessor,
            String commandFingerprint,
            String reasonCode,
            Instant publishedAt) {
        return new CapabilityCorpusClusterPublication(
                value.schemaVersion(),
                CapabilityObservationTestFixtures.fingerprint('0'),
                commandFingerprint,
                value.scope(),
                value.clusterId(),
                revision,
                predecessor,
                value.capabilityRef(),
                value.corpusPublicationRef(),
                value.corpusRevisionRef(),
                value.publicationPolicyRef(),
                value.clusterPolicyRef(),
                value.validationRef(),
                value.representativeSource(),
                value.members(),
                value.matchRequestPointers(),
                value.identityMode(),
                value.identityProjections(),
                value.distinctIdentityCount(),
                value.holdout(),
                value.confidence(),
                value.reviewTicketRef(),
                reasonCode,
                value.reviewedBy(),
                publishedAt,
                value.usableUntil());
    }

    private static void assertReason(
            Runnable action,
            CapabilityCorpusClusterRepository.Reason reason) {
        assertThatThrownBy(action::run)
                .isInstanceOf(
                        CapabilityCorpusClusterRepository.Violation.class)
                .extracting(failure ->
                        ((CapabilityCorpusClusterRepository.Violation)
                                failure).reason())
                .isEqualTo(reason);
    }
}
