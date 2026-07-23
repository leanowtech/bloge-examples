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

class DatabaseCapabilityObservationReviewRepositoryTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final CapabilityCorpusIntegrity integrity =
            new CapabilityCorpusIntegrity(mapper);
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DatabaseCapabilityObservationReviewRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(database);
        repository = new DatabaseCapabilityObservationReviewRepository(
                jdbc, mapper, integrity);
        repository.init();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void persistsTerminalReviewAcrossRestartAndRecoversExactRetry() {
        CapabilityObservationReview review =
                review("org-a", "observation-a", "REMEDIATION");

        assertThat(repository.append(review)).isEqualTo(review);
        assertThat(repository.append(review)).isEqualTo(review);
        DatabaseCapabilityObservationReviewRepository restarted =
                new DatabaseCapabilityObservationReviewRepository(
                        jdbc, mapper, integrity);
        restarted.init();

        assertThat(restarted.find(
                review.scope(), review.observationRef().id())).contains(review);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM mirror_capability_observation_reviews",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void isolatesSameObservationIdByCompleteEnterpriseScope() {
        CapabilityObservationReview orgA =
                review("org-a", "shared-observation", "ORG_A");
        CapabilityObservationReview orgB =
                review("org-b", "shared-observation", "ORG_B");

        repository.append(orgA);
        repository.append(orgB);

        assertThat(repository.find(orgA.scope(), "shared-observation"))
                .contains(orgA);
        assertThat(repository.find(orgB.scope(), "shared-observation"))
                .contains(orgB);
    }

    @Test
    void conflictsInsteadOfOverwritingTerminalReview() {
        CapabilityObservationReview first =
                review("org-a", "observation-conflict", "FIRST");
        CapabilityObservationReview different =
                review("org-a", "observation-conflict", "SECOND");
        repository.append(first);

        assertThatThrownBy(() -> repository.append(different))
                .isInstanceOf(CapabilityObservationReviewRepository.Violation.class)
                .extracting(failure ->
                        ((CapabilityObservationReviewRepository.Violation) failure)
                                .reason())
                .isEqualTo(
                        CapabilityObservationReviewRepository.Reason
                                .REVIEW_CONFLICT);
    }

    @Test
    void detectsTamperedIndexAndCanonicalJson() {
        CapabilityObservationReview review =
                review("org-a", "observation-tamper", "TAMPER");
        repository.append(review);

        jdbc.update("""
                UPDATE mirror_capability_observation_reviews
                SET reviewed_by = 'different-reviewer'
                WHERE observation_id = ?
                """, review.observationRef().id());
        assertCorrupt(review);

        jdbc.update("""
                UPDATE mirror_capability_observation_reviews
                SET reviewed_by = ?, review_json = '{}'
                WHERE observation_id = ?
                """, review.reviewedBy(), review.observationRef().id());
        assertCorrupt(review);
    }

    private void assertCorrupt(CapabilityObservationReview review) {
        assertThatThrownBy(() -> repository.find(
                review.scope(), review.observationRef().id()))
                .isInstanceOf(CapabilityObservationReviewRepository.Violation.class)
                .extracting(failure ->
                        ((CapabilityObservationReviewRepository.Violation) failure)
                                .reason())
                .isEqualTo(
                        CapabilityObservationReviewRepository.Reason
                                .STORED_STATE_CORRUPT);
    }

    private CapabilityObservationReview review(
            String organization, String observationId, String reasonCode) {
        CapabilityObservationRepository.StoredObservation source =
                CapabilityCorpusTestFixtures.quarantined(
                        mapper,
                        CapabilityObservationTestFixtures.scope(organization),
                        observationId);
        CapabilityObservationReviewRequest request =
                CapabilityCorpusTestFixtures.reviewRequest(source);
        request = new CapabilityObservationReviewRequest(
                request.schemaVersion(),
                request.observationRef(),
                request.admissionRef(),
                request.disposition(),
                request.reviewTicketRef(),
                reasonCode);
        return integrity.sealReview(new CapabilityObservationReview(
                "",
                CapabilityObservationTestFixtures.fingerprint('0'),
                integrity.reviewCommandFingerprint(request),
                source.envelope().material().scope(),
                source.envelope().artifactRef(),
                source.admission().artifactRef(),
                request.disposition(),
                request.reviewTicketRef(),
                request.reasonCode(),
                "reviewer",
                Instant.parse("2026-07-23T00:00:00Z")));
    }
}
