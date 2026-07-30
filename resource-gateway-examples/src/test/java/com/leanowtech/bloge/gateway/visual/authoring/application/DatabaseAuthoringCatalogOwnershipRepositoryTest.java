package com.leanowtech.bloge.gateway.visual.authoring.application;

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

class DatabaseAuthoringCatalogOwnershipRepositoryTest {

    private static final AuthoringScope SCOPE = new AuthoringScope(
            "tenant-a", "knowledge-governance", "tool-studio", "test", "local");
    private static final AuthoringScope OTHER_SCOPE = new AuthoringScope(
            "tenant-b", "knowledge-governance", "tool-studio", "test", "local");

    private EmbeddedDatabase database;
    private DatabaseAuthoringCatalogOwnershipRepository first;
    private DatabaseAuthoringCatalogOwnershipRepository second;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        first = new DatabaseAuthoringCatalogOwnershipRepository(jdbc);
        second = new DatabaseAuthoringCatalogOwnershipRepository(jdbc);
        first.init();
        second.init();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void atomicallyKeepsOneEnterpriseOwnerAcrossRepositoryInstances() {
        Instant claimedAt = Instant.parse("2026-07-31T08:00:00Z");
        AuthoringCatalogOwnershipRepository.Ownership claimed =
                first.claim(SCOPE, "support-library", "alice", claimedAt);

        assertThat(second.claim(
                SCOPE,
                "support-library",
                "another-replica",
                claimedAt.plusSeconds(1)))
                .isEqualTo(claimed);
        assertThatThrownBy(() -> second.claim(
                OTHER_SCOPE,
                "support-library",
                "mallory",
                claimedAt.plusSeconds(2)))
                .isInstanceOf(AuthoringCatalogOwnershipConflictException.class);
        assertThat(second.find("support-library")).contains(claimed);
    }
}
