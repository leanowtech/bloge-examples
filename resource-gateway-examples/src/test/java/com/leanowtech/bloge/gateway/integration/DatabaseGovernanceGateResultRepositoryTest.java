package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseGovernanceGateResultRepositoryTest {
    private JdbcTemplate jdbc;
    private ObjectMapper mapper;
    private TransactionTemplate transaction;
    private DatabaseIntegrationChangeEventOutbox outbox;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).build();
        jdbc = new JdbcTemplate(dataSource);
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        outbox = new DatabaseIntegrationChangeEventOutbox(jdbc, mapper);
        outbox.init();
    }

    @Test
    void persistsGateAndScopedChangeEventAtomicallyAndDeduplicatesAcrossInstances() {
        DatabaseGovernanceGateResultRepository first = repository(outbox);
        GovernanceGateResult result = result("gate-1", "BLOCKED");

        GovernanceGateResult stored = transaction.execute(status -> first.create(result));
        DatabaseGovernanceGateResultRepository restarted = repository(outbox);
        GovernanceGateResult replayed = transaction.execute(status -> restarted.create(result));

        assertThat(replayed).isEqualTo(stored);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM governance_gate_results", Long.class)).isOne();
        assertThat(outbox.read(0, outbox.highWaterSequence(), "tenant-a", "prod", 10)).singleElement()
                .satisfies(event -> {
                    assertThat(event.eventType()).isEqualTo("GOVERNANCE_GATE_RESULT_RECEIVED");
                    assertThat(event.tenantId()).isEqualTo("tenant-a");
                    assertThat(event.environmentId()).isEqualTo("prod");
                    assertThat(event.aggregate().id()).isEqualTo("gate-1");
                });

        assertThatThrownBy(() -> transaction.execute(status -> restarted.create(result("gate-1", "PASSED"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("different content");
    }

    @Test
    void outboxFailureRollsBackGateResult() {
        DatabaseGovernanceGateResultRepository failing = repository(new FailingIntegrationChangeEventOutbox());

        assertThatThrownBy(() -> transaction.execute(status -> failing.create(result("gate-rollback", "BLOCKED"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("simulated outbox failure");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM governance_gate_results", Long.class)).isZero();
    }

    private DatabaseGovernanceGateResultRepository repository(
            com.leanowtech.bloge.gateway.visual.change.VisualChangeEventPublisher publisher) {
        DatabaseGovernanceGateResultRepository repository = new DatabaseGovernanceGateResultRepository(
                jdbc, mapper, publisher);
        repository.init();
        return repository;
    }

    private static GovernanceGateResult result(String id, String status) {
        return new GovernanceGateResult("", id,
                new GovernanceGateResult.Target("GRAPH_DRAFT", "draft-1", 3, "sha256:draft",
                        "tenant-a", "knowledge", "prod"), status, List.of(),
                Instant.parse("2026-07-13T00:00:00Z"), null, "", null);
    }
}
