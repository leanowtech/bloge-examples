package com.leanowtech.bloge.gateway.solution.journey;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the independent audit contains identity coordinates and no business payload columns. */
class DatabaseBusinessGoldenReviewAuditRepositoryTest {
    private JdbcTemplate jdbc;
    private DatabaseBusinessGoldenReviewAuditRepository repository;

    @BeforeEach
    void setUp() {
        var database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).build();
        new ResourceDatabasePopulator(new ClassPathResource(
                "solution/h2-business-golden-review-audit.sql")).execute(database);
        jdbc = new JdbcTemplate(database);
        repository = new DatabaseBusinessGoldenReviewAuditRepository(jdbc);
    }

    @Test
    void appendsAnImmutableHumanAccessCoordinate() {
        var event = new BusinessGoldenReviewAuditRepository.BusinessGoldenReviewAccess(
                UUID.randomUUID().toString(), identity(), "caseSet:journey:cancel", "g1",
                "GOLDEN_MATERIAL_REVIEW", "ACCEPTED", null);

        var persisted = repository.append(event);

        assertThat(persisted.occurredAt()).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT actor_id FROM rg_business_golden_review_audit", String.class))
                .isEqualTo("cx-owner");
        assertThat(jdbc.queryForObject(
                "SELECT outcome FROM rg_business_golden_review_audit", String.class))
                .isEqualTo("ACCEPTED");
    }

    @Test
    void schemaCannotRepresentBusinessValuesOrProtectedReceipts() {
        assertThat(jdbc.queryForList("""
                        SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
                        WHERE TABLE_NAME = 'RG_BUSINESS_GOLDEN_REVIEW_AUDIT'
                        """, String.class))
                .noneMatch(column -> {
                    String normalized = column.toLowerCase(Locale.ROOT);
                    return normalized.contains("payload") || normalized.contains("given")
                            || normalized.contains("expected") || normalized.contains("intent")
                            || normalized.contains("receipt") || normalized.contains("value");
                });
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test", "sg",
                "HUMAN", "cx-owner", "", "SOLUTION_GOLDEN_REVIEW", "corr-review",
                java.util.Set.of(), "INTERNAL", "");
    }
}
