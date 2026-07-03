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
 * Tests for H2-backed executable lowering integration persistence.
 */
class DatabaseVisualExecutableLoweringIntegrationRepositoryTest {

    private DatabaseVisualExecutableLoweringIntegrationRepository repository;
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
        repository = new DatabaseVisualExecutableLoweringIntegrationRepository(jdbc, objectMapper);
        repository.init();
    }

    @Test
    void createAssignsIdentityAndPersistsIntegration() {
        VisualExecutableLoweringIntegration stored = repository.create(integration(""));

        assertThat(stored.integrationId()).isNotBlank();
        assertThat(stored.revision()).isEqualTo(1);
        assertThat(stored.createdAt()).isNotNull();
        assertThat(stored.updatedAt()).isNotNull();
        assertThat(stored.state()).isEqualTo("active");
        assertThat(stored.loweringMode()).isEqualTo("native");
        assertThat(repository.find(stored.integrationId())).contains(stored);
        assertThat(repository.findActiveByActivationId("risk-activation-1")).contains(stored);
    }

    @Test
    void persistenceSurvivesReInit() {
        VisualExecutableLoweringIntegration stored = repository.create(integration("risk-integration-1"));

        DatabaseVisualExecutableLoweringIntegrationRepository reloaded =
                new DatabaseVisualExecutableLoweringIntegrationRepository(jdbc, objectMapper);
        reloaded.init();

        assertThat(reloaded.find("risk-integration-1")).contains(stored);
        assertThat(reloaded.findActiveByActivationId("risk-activation-1")).contains(stored);
        assertThat(reloaded.all()).containsExactly(stored);
    }

    @Test
    void updatePersistsIntegrationReplacementState() {
        VisualExecutableLoweringIntegration stored = repository.create(integration("risk-integration-2"));
        VisualExecutableLoweringIntegration inactive = new VisualExecutableLoweringIntegration(
                stored.schemaVersion(),
                stored.integrationId(),
                stored.revision(),
                VisualExecutableLoweringIntegration.STATE_INACTIVE,
                "info",
                stored.activationId(),
                stored.activationRevision(),
                stored.bindingId(),
                stored.bindingRevision(),
                stored.operatorRef(),
                stored.operatorFingerprint(),
                stored.adapterKind(),
                stored.entrypoint(),
                stored.runtimeEnvironment(),
                stored.loweringMode(),
                stored.executorKind(),
                stored.executorEntrypoint(),
                stored.executorOwner(),
                stored.integratedBy(),
                stored.changeSource(),
                stored.reason(),
                stored.evidence(),
                stored.createdAt(),
                Instant.now()
        );

        VisualExecutableLoweringIntegration updated = repository.update(inactive);

        assertThat(updated.state()).isEqualTo("inactive");
        assertThat(repository.findActiveByActivationId("risk-activation-1")).isEmpty();

        DatabaseVisualExecutableLoweringIntegrationRepository reloaded =
                new DatabaseVisualExecutableLoweringIntegrationRepository(jdbc, objectMapper);
        reloaded.init();

        assertThat(reloaded.find("risk-integration-2")).contains(updated);
        assertThat(reloaded.findActiveByActivationId("risk-activation-1")).isEmpty();
    }

    private static VisualExecutableLoweringIntegration integration(String integrationId) {
        return new VisualExecutableLoweringIntegration(
                VisualExecutableLoweringIntegration.SCHEMA_VERSION,
                integrationId,
                0,
                VisualExecutableLoweringIntegration.STATE_ACTIVE,
                "success",
                "risk-activation-1",
                2,
                "risk-binding-1",
                3,
                "risk:eligibility",
                "sha256:contract",
                "native",
                "com.acme.risk.RiskEligibilityOperator",
                "prod",
                "native",
                "bloge-operator-registry",
                "operator:risk:eligibility",
                "operator-platform",
                "runtime-platform",
                "repository-test",
                "Executor bridge is available.",
                List.of(new VisualExecutableLoweringIntegration.Evidence(
                        "executor-test",
                        "executor-suite:risk-v1",
                        "Executor bridge suite passed.")),
                Instant.EPOCH,
                Instant.EPOCH
        );
    }
}
