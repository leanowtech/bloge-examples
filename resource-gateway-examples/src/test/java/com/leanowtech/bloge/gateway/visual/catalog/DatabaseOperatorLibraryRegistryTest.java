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
    void operatorsSkipNullEntriesFromPersistedLibrary() {
        registry.upsert(libraryWithNullEntry("risk-policy", VisualCatalogTestSupport.eligibilityOperator("integer")));

        DatabaseOperatorLibraryRegistry reloaded = new DatabaseOperatorLibraryRegistry(jdbc, objectMapper);
        reloaded.init();

        assertThat(reloaded.operators(false))
                .extracting(OperatorDefinition::operatorRef)
                .containsExactly("risk:eligibility");
    }

    @Test
    void operatorsSkipEntriesWithNullPortsFromPersistedLibrary() {
        registry.upsert(libraryWithNullPortEntry());

        DatabaseOperatorLibraryRegistry reloaded = new DatabaseOperatorLibraryRegistry(jdbc, objectMapper);
        reloaded.init();

        assertThat(reloaded.operators(false))
                .extracting(OperatorDefinition::operatorRef)
                .doesNotContain("risk:malformedPorts");
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
    void duplicateCheckIgnoresNullOperatorEntries() {
        registry.upsert(libraryWithNullEntry("risk-policy", VisualCatalogTestSupport.eligibilityOperator("integer")));
        OperatorLibrary numeric = libraryWithNullEntry("numeric-policy",
                VisualCatalogTestSupport.numericPassOperator());

        registry.upsert(numeric);

        assertThat(registry.operators(false))
                .extracting(OperatorDefinition::operatorRef)
                .containsExactly("risk:numericPass", "risk:eligibility");
    }

    @Test
    void deleteRemovesLibrary() {
        registry.upsert(VisualCatalogTestSupport.eligibilityLibrary("integer"));

        registry.delete("risk-policy");

        assertThat(registry.find("risk-policy")).isEmpty();
    }

    private static OperatorLibrary libraryWithNullEntry(String libraryId, OperatorDefinition operator) {
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                libraryId,
                libraryId,
                "1.0.0",
                "risk-team",
                "ACTIVE",
                java.util.Arrays.asList(null, operator)
        );
    }

    private static OperatorLibrary libraryWithNullPortEntry() {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition malformed = new OperatorDefinition(
                base.schemaVersion(),
                "risk:malformedPorts",
                base.operatorVersion(),
                base.display(),
                base.source(),
                new OperatorDefinition.Ports(java.util.Arrays.asList(null, base.ports().inputs().getFirst()),
                        base.ports().outputs()),
                base.configSchema(),
                base.capabilities(),
                base.policy(),
                base.lowering(),
                base.diagnostics()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "malformed-ports",
                "Malformed ports",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                java.util.List.of(malformed)
        );
    }
}
