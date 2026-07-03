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
 * Tests for H2-backed runtime adapter activation persistence.
 */
class DatabaseVisualRuntimeAdapterActivationRepositoryTest {

    private DatabaseVisualRuntimeAdapterActivationRepository repository;
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
        repository = new DatabaseVisualRuntimeAdapterActivationRepository(jdbc, objectMapper);
        repository.init();
    }

    @Test
    void createAssignsIdentityAndPersistsActivation() {
        VisualRuntimeAdapterActivation stored = repository.create(activation(""));

        assertThat(stored.activationId()).isNotBlank();
        assertThat(stored.revision()).isEqualTo(1);
        assertThat(stored.createdAt()).isNotNull();
        assertThat(stored.updatedAt()).isNotNull();
        assertThat(stored.state()).isEqualTo("active");
        assertThat(stored.healthState()).isEqualTo("healthy");
        assertThat(repository.find(stored.activationId())).contains(stored);
        assertThat(repository.findActiveByBindingId("risk-binding-1")).contains(stored);
    }

    @Test
    void persistenceSurvivesReInit() {
        VisualRuntimeAdapterActivation stored = repository.create(activation("risk-activation-1"));

        DatabaseVisualRuntimeAdapterActivationRepository reloaded =
                new DatabaseVisualRuntimeAdapterActivationRepository(jdbc, objectMapper);
        reloaded.init();

        assertThat(reloaded.find("risk-activation-1")).contains(stored);
        assertThat(reloaded.findActiveByBindingId("risk-binding-1")).contains(stored);
        assertThat(reloaded.all()).containsExactly(stored);
    }

    @Test
    void updatePersistsActivationReplacementState() {
        VisualRuntimeAdapterActivation stored = repository.create(activation("risk-activation-2"));
        VisualRuntimeAdapterActivation inactive = new VisualRuntimeAdapterActivation(
                stored.schemaVersion(),
                stored.activationId(),
                stored.revision() + 1,
                VisualRuntimeAdapterActivation.STATE_INACTIVE,
                "info",
                stored.bindingId(),
                stored.bindingRevision(),
                stored.operatorRef(),
                stored.operatorFingerprint(),
                stored.adapterKind(),
                stored.entrypoint(),
                stored.runtimeOwner(),
                stored.runtimeEnvironment(),
                stored.healthState(),
                stored.activatedBy(),
                stored.changeSource(),
                stored.reason(),
                stored.evidence(),
                stored.createdAt(),
                Instant.now()
        );

        VisualRuntimeAdapterActivation updated = repository.update(inactive);

        assertThat(updated.state()).isEqualTo("inactive");
        assertThat(repository.findActiveByBindingId("risk-binding-1")).isEmpty();

        DatabaseVisualRuntimeAdapterActivationRepository reloaded =
                new DatabaseVisualRuntimeAdapterActivationRepository(jdbc, objectMapper);
        reloaded.init();

        assertThat(reloaded.find("risk-activation-2")).contains(updated);
        assertThat(reloaded.findActiveByBindingId("risk-binding-1")).isEmpty();
    }

    private static VisualRuntimeAdapterActivation activation(String activationId) {
        return new VisualRuntimeAdapterActivation(
                VisualRuntimeAdapterActivation.SCHEMA_VERSION,
                activationId,
                0,
                VisualRuntimeAdapterActivation.STATE_ACTIVE,
                "success",
                "risk-binding-1",
                2,
                "risk:eligibility",
                "sha256:contract",
                "native",
                "com.acme.risk.RiskEligibilityOperator",
                "risk-platform",
                "prod",
                VisualRuntimeAdapterActivation.HEALTH_HEALTHY,
                "runtime-platform",
                "repository-test",
                "Deployment is healthy.",
                List.of(new VisualRuntimeAdapterActivation.Evidence(
                        "health-check",
                        "deployment:risk-v1",
                        "Readiness probe is healthy.")),
                Instant.EPOCH,
                Instant.EPOCH
        );
    }
}
