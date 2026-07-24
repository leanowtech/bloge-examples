package com.leanowtech.bloge.gateway.integration.mirror;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseScenarioRehearsalBatchLifecycleAuditRepositoryTest {
    private static final CapabilitySnapshot.Scope SCOPE =
            new CapabilitySnapshot.Scope(
                    "tenant-a", "org-a", "support", "test", "sg");
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DatabaseScenarioRehearsalBatchLifecycleAuditRepository
            repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(database);
        repository =
                new DatabaseScenarioRehearsalBatchLifecycleAuditRepository(
                        jdbc);
        repository.init();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void appendsWithDatabaseCoordinatesAndReadsOnlyExactScope() {
        ScenarioRehearsalBatchLifecycleAuditEvent persisted =
                repository.append(admitted());

        assertThat(persisted.sequence()).isPositive();
        assertThat(persisted.occurredAt()).isNotNull();
        assertThat(repository.lifecycle(
                SCOPE, "job-001", 10))
                .containsExactly(persisted);
        assertThat(repository.lifecycle(
                new CapabilitySnapshot.Scope(
                        "tenant-a",
                        "org-b",
                        "support",
                        "test",
                        "sg"),
                "job-001",
                10)).isEmpty();
    }

    @Test
    void tableCannotRepresentBusinessPayloadOrCredentials() {
        List<String> columns = jdbc.queryForList(
                """
                        SELECT COLUMN_NAME
                        FROM INFORMATION_SCHEMA.COLUMNS
                        WHERE TABLE_NAME =
                              'SCENARIO_REHEARSAL_BATCH_LIFECYCLE_AUDIT'
                        ORDER BY ORDINAL_POSITION
                        """,
                String.class);

        assertThat(columns)
                .noneMatch(column -> {
                    String normalized =
                            column.toLowerCase(Locale.ROOT);
                    return normalized.contains("payload")
                            || normalized.contains("fixture")
                            || normalized.contains("input")
                            || normalized.contains("output")
                            || normalized.contains("credential")
                            || normalized.contains("secret")
                            || normalized.contains("exception")
                            || normalized.contains("stack");
                });
    }

    @Test
    void rejectsTerminalFactsWithoutSignedBatchEvidence() {
        assertThatThrownBy(() ->
                new ScenarioRehearsalBatchLifecycleAuditEvent(
                        0,
                        null,
                        SCOPE,
                        "job-001",
                        "batch-001",
                        "sha256:" + "a".repeat(64),
                        ScenarioRehearsalBatchLifecycleAuditEvent
                                .Transition.TERMINALIZED,
                        ScenarioRehearsalBatchJob.Status.CANCELLED,
                        -1,
                        ScenarioRehearsalBatchLifecycleAuditEvent
                                .ItemStatus.NONE,
                        0,
                        "",
                        0,
                        "",
                        "RG.MIRROR.REHEARSAL_BATCH.CANCELLED"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnstructuredCancellationReasonsAtTheDomainBoundary() {
        assertThatThrownBy(() ->
                new ScenarioRehearsalBatchRepository.Cancellation(
                        SCOPE,
                        "job-001",
                        "cancel-001",
                        "human readable reason"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason code");
    }

    private static ScenarioRehearsalBatchLifecycleAuditEvent
    admitted() {
        return new ScenarioRehearsalBatchLifecycleAuditEvent(
                0,
                null,
                SCOPE,
                "job-001",
                "batch-001",
                "sha256:" + "a".repeat(64),
                ScenarioRehearsalBatchLifecycleAuditEvent.Transition
                        .ADMITTED,
                ScenarioRehearsalBatchJob.Status.QUEUED,
                -1,
                ScenarioRehearsalBatchLifecycleAuditEvent.ItemStatus
                        .NONE,
                0,
                "",
                0,
                "",
                "");
    }
}
