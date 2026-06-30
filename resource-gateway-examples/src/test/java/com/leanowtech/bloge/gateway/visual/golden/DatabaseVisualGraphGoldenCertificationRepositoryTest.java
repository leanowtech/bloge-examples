package com.leanowtech.bloge.gateway.visual.golden;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for H2-backed visual graph golden certification persistence.
 */
class DatabaseVisualGraphGoldenCertificationRepositoryTest {

    private JdbcTemplate jdbc;
    private ObjectMapper objectMapper;
    private DatabaseVisualGraphGoldenCertificationRepository repository;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(dataSource);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        repository = new DatabaseVisualGraphGoldenCertificationRepository(jdbc, objectMapper);
        repository.init();
    }

    @Test
    void savePersistsCertification() {
        VisualGraphGoldenCertification stored = repository.save(certification(true));

        assertThat(repository.find("publication-1")).contains(stored);
    }

    @Test
    void persistenceSurvivesReInit() {
        VisualGraphGoldenCertification stored = repository.save(certification(true));

        DatabaseVisualGraphGoldenCertificationRepository reloaded =
                new DatabaseVisualGraphGoldenCertificationRepository(jdbc, objectMapper);
        reloaded.init();

        assertThat(reloaded.find("publication-1")).contains(stored);
    }

    @Test
    void saveReplacesLatestCertificationForPublication() {
        repository.save(certification(false));
        VisualGraphGoldenCertification updated = repository.save(certification(true));

        assertThat(repository.find("publication-1")).contains(updated);
        assertThat(repository.find("publication-1").orElseThrow().certified()).isTrue();
    }

    private static VisualGraphGoldenCertification certification(boolean certified) {
        return new VisualGraphGoldenCertification(
                "",
                "publication-1",
                certified,
                1,
                certified ? 1 : 0,
                certified ? 0 : 1,
                List.of("run-1"),
                certified ? List.of() : List.of(VisualDiagnostic.error(
                        "visual.golden.suiteFailed",
                        "suite failed",
                        "/results")),
                null
        );
    }
}
