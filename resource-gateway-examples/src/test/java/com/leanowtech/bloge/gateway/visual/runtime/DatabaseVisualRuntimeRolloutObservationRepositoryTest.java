package com.leanowtech.bloge.gateway.visual.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for H2-backed runtime rollout observation persistence.
 */
class DatabaseVisualRuntimeRolloutObservationRepositoryTest {

    private DatabaseVisualRuntimeRolloutObservationRepository repository;
    private JdbcTemplate jdbc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(dataSource);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        repository = new DatabaseVisualRuntimeRolloutObservationRepository(jdbc, objectMapper);
        repository.init();
    }

    @Test
    void createAssignsIdentityAndPersistsObservation() {
        VisualRuntimeRolloutObservation stored = repository.create(observation(""));

        assertThat(stored.observationId()).isNotBlank();
        assertThat(stored.revision()).isEqualTo(1);
        assertThat(stored.createdAt()).isNotNull();
        assertThat(stored.updatedAt()).isNotNull();
        assertThat(stored.state()).isEqualTo("healthy");
        assertThat(stored.rolloutStrategy()).isEqualTo("canary");
        assertThat(repository.find(stored.observationId())).contains(stored);
        assertThat(repository.all()).containsExactly(stored);
    }

    @Test
    void persistenceSurvivesReInit() {
        VisualRuntimeRolloutObservation stored = repository.create(observation("risk-rollout-1"));

        DatabaseVisualRuntimeRolloutObservationRepository reloaded =
                new DatabaseVisualRuntimeRolloutObservationRepository(jdbc, objectMapper);
        reloaded.init();

        assertThat(reloaded.find("risk-rollout-1")).contains(stored);
        assertThat(reloaded.all()).containsExactly(stored);
    }

    @Test
    void allSortsNewestObservationFirst() {
        VisualRuntimeRolloutObservation first = repository.create(observation(
                "risk-rollout-first",
                Instant.parse("2026-07-04T00:00:00Z")));
        VisualRuntimeRolloutObservation second = repository.create(observation(
                "risk-rollout-second",
                Instant.parse("2026-07-04T00:10:00Z")));

        assertThat(repository.all()).containsExactly(second, first);
    }

    private static VisualRuntimeRolloutObservation observation(String observationId) {
        return observation(observationId, Instant.parse("2026-07-04T00:00:00Z"));
    }

    private static VisualRuntimeRolloutObservation observation(String observationId, Instant observedAt) {
        return new VisualRuntimeRolloutObservation(
                VisualRuntimeRolloutObservation.SCHEMA_VERSION,
                observationId,
                0,
                VisualRuntimeRolloutObservation.STATE_HEALTHY,
                "success",
                "risk-activation-1",
                1,
                "risk-binding-1",
                2,
                "risk:eligibility",
                "sha256:contract",
                "native",
                "prod",
                "canary",
                5,
                "canary",
                false,
                "",
                "runtime-platform",
                "repository-test",
                "Canary metrics are healthy.",
                List.of(new VisualRuntimeRolloutObservation.Evidence(
                        "canary-metric",
                        "rollout:risk-v1",
                        "Canary SLO guardrails passed.")),
                observedAt,
                Instant.EPOCH,
                Instant.EPOCH
        );
    }
}
