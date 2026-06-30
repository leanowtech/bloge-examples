package com.leanowtech.bloge.gateway.visual.golden;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for H2-backed visual graph golden case persistence.
 */
class DatabaseVisualGraphGoldenCaseRepositoryTest {

    private JdbcTemplate jdbc;
    private ObjectMapper objectMapper;
    private DatabaseVisualGraphGoldenCaseRepository repository;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(dataSource);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        repository = new DatabaseVisualGraphGoldenCaseRepository(jdbc, objectMapper);
        repository.init();
    }

    @Test
    void saveAssignsCaseIdAndPersistsRecord() {
        VisualGraphGoldenCase stored = repository.save(caseRecord(""));

        assertThat(stored.caseId()).isNotBlank();
        assertThat(repository.find(stored.caseId())).contains(stored);
        assertThat(repository.findByPublicationId("publication-1")).containsExactly(stored);
    }

    @Test
    void persistenceSurvivesReInit() {
        VisualGraphGoldenCase stored = repository.save(caseRecord("case-1"));

        DatabaseVisualGraphGoldenCaseRepository reloaded =
                new DatabaseVisualGraphGoldenCaseRepository(jdbc, objectMapper);
        reloaded.init();

        assertThat(reloaded.find("case-1")).contains(stored);
        assertThat(reloaded.all()).containsExactly(stored);
    }

    @Test
    void persistencePreservesAssertions() {
        VisualGraphGoldenCase stored = repository.save(new VisualGraphGoldenCase(
                "",
                "case-1",
                "publication-1",
                "approval assertion",
                "",
                "eligibility",
                Map.of("score", 760),
                Map.of("legacy", "ignored"),
                List.of(new VisualGraphGoldenAssertion(
                        VisualGraphGoldenAssertion.Mode.PATH_EQUALS,
                        "/approved",
                        true)),
                null
        ));

        DatabaseVisualGraphGoldenCaseRepository reloaded =
                new DatabaseVisualGraphGoldenCaseRepository(jdbc, objectMapper);
        reloaded.init();

        assertThat(reloaded.find("case-1")).contains(stored);
        assertThat(reloaded.find("case-1").orElseThrow().assertions())
                .containsExactly(new VisualGraphGoldenAssertion(
                        VisualGraphGoldenAssertion.Mode.PATH_EQUALS,
                        "/approved",
                        true));
    }

    @Test
    void saveReplacesExistingCase() {
        repository.save(caseRecord("case-1"));
        VisualGraphGoldenCase updated = repository.save(new VisualGraphGoldenCase(
                "",
                "case-1",
                "publication-1",
                "changed",
                "",
                "",
                Map.of("score", 760),
                Map.of("approved", true),
                null
        ));

        assertThat(repository.find("case-1")).contains(updated);
        assertThat(repository.all()).hasSize(1);
    }

    private static VisualGraphGoldenCase caseRecord(String caseId) {
        return new VisualGraphGoldenCase(
                "",
                caseId,
                "publication-1",
                "prime approval",
                "",
                "eligibility",
                Map.of("score", 720),
                Map.of("approved", true),
                null
        );
    }
}
