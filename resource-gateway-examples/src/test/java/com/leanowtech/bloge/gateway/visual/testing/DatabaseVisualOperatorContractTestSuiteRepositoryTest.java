package com.leanowtech.bloge.gateway.visual.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leanowtech.bloge.gateway.integration.FailingIntegrationChangeEventOutbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseVisualOperatorContractTestSuiteRepositoryTest {
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(dataSource);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void preservesImmutableRevisionsAcrossRestart() {
        DatabaseVisualOperatorContractTestSuiteRepository repository = repository(null);
        VisualOperatorContractTestSuite first = repository.save(suite("Suite v1"));
        repository.save(suite("Suite v2"));

        DatabaseVisualOperatorContractTestSuiteRepository reloaded = repository(null);

        assertThat(reloaded.revision("suite-risk")).isEqualTo(2);
        assertThat(reloaded.findRevision("suite-risk", 1)).contains(first);
        assertThat(reloaded.find("suite-risk")).get()
                .extracting(VisualOperatorContractTestSuite::displayName)
                .isEqualTo("Suite v2");
    }

    @Test
    void assetAndChangeEventRollBackTogetherWhenOutboxAppendFails() {
        DatabaseVisualOperatorContractTestSuiteRepository failing = repository(
                new FailingIntegrationChangeEventOutbox());
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> failing.save(suite("Atomic"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated outbox failure");

        assertThat(failing.find("suite-risk")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM visual_operator_contract_test_suites", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM visual_operator_contract_test_suite_revisions", Long.class)).isZero();
    }

    private DatabaseVisualOperatorContractTestSuiteRepository repository(
            com.leanowtech.bloge.gateway.integration.IntegrationChangeEventOutbox outbox) {
        DatabaseVisualOperatorContractTestSuiteRepository repository = outbox == null
                ? new DatabaseVisualOperatorContractTestSuiteRepository(jdbc, objectMapper)
                : new DatabaseVisualOperatorContractTestSuiteRepository(jdbc, objectMapper, outbox);
        repository.init();
        return repository;
    }

    private static VisualOperatorContractTestSuite suite(String name) {
        return new VisualOperatorContractTestSuite("suite-risk", name, "", List.of("regression"),
                new VisualOperatorContractTestSuiteRequest("risk:score", List.of()));
    }
}
