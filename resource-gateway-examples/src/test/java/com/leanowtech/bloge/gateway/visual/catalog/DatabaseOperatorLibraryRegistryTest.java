package com.leanowtech.bloge.gateway.visual.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for H2-backed visual operator library persistence.
 */
class DatabaseOperatorLibraryRegistryTest {

    private DatabaseOperatorLibraryRegistry registry;
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
        registry = new DatabaseOperatorLibraryRegistry(jdbc, objectMapper);
        registry.init();
    }

    @Test
    void upsertThenFind() {
        OperatorLibrary library = VisualCatalogTestSupport.eligibilityLibrary("integer");

        registry.upsert(library);

        assertThat(registry.find("risk-policy")).contains(library);
        assertThat(registry.all()).hasSize(1);
    }

    @Test
    void persistenceSurvivesReInit() {
        registry.upsert(VisualCatalogTestSupport.eligibilityLibrary("integer"));

        DatabaseOperatorLibraryRegistry reloaded = new DatabaseOperatorLibraryRegistry(jdbc, objectMapper);
        reloaded.init();

        assertThat(reloaded.find("risk-policy")).isPresent();
        assertThat(reloaded.operators(false))
                .extracting(OperatorDefinition::operatorRef)
                .containsExactly("risk:eligibility");
    }

    @Test
    void duplicateOperatorRefAcrossLibrariesIsRejected() {
        registry.upsert(VisualCatalogTestSupport.eligibilityLibrary("integer"));
        OperatorLibrary duplicate = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-policy-copy",
                "Copy",
                "1.0.0",
                "",
                "ACTIVE",
                java.util.List.of(VisualCatalogTestSupport.eligibilityOperator("integer"))
        );

        assertThatThrownBy(() -> registry.upsert(duplicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already provided");
    }

    @Test
    void deleteRemovesLibrary() {
        registry.upsert(VisualCatalogTestSupport.eligibilityLibrary("integer"));

        registry.delete("risk-policy");

        assertThat(registry.find("risk-policy")).isEmpty();
    }
}
