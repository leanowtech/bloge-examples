package com.leanowtech.bloge.gateway.visual.asset;

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
 * Tests for H2-backed runtime implementation binding persistence.
 */
class DatabaseVisualRuntimeBindingImplementationRepositoryTest {

    private DatabaseVisualRuntimeBindingImplementationRepository repository;
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
        repository = new DatabaseVisualRuntimeBindingImplementationRepository(jdbc, objectMapper);
        repository.init();
    }

    @Test
    void createAssignsIdentityAndPersistsBinding() {
        VisualRuntimeBindingImplementationBinding stored = repository.create(binding(""));

        assertThat(stored.bindingId()).isNotBlank();
        assertThat(stored.revision()).isEqualTo(1);
        assertThat(stored.createdAt()).isNotNull();
        assertThat(stored.updatedAt()).isNotNull();
        assertThat(stored.state()).isEqualTo("ready-to-bind");
        assertThat(repository.find(stored.bindingId())).contains(stored);
    }

    @Test
    void persistenceSurvivesReInit() {
        VisualRuntimeBindingImplementationBinding stored = repository.create(binding("risk-binding-1"));

        DatabaseVisualRuntimeBindingImplementationRepository reloaded =
                new DatabaseVisualRuntimeBindingImplementationRepository(jdbc, objectMapper);
        reloaded.init();

        assertThat(reloaded.find("risk-binding-1")).contains(stored);
        assertThat(reloaded.all()).containsExactly(stored);
    }

    @Test
    void updatePersistsLifecycleTransition() {
        VisualRuntimeBindingImplementationBinding stored = repository.create(binding("risk-binding-2"));
        Instant now = Instant.now();
        VisualRuntimeBindingImplementationBinding updated = stored.withLifecycleTransition(
                VisualRuntimeBindingImplementationBinding.STATE_BOUND,
                "success",
                null,
                null,
                new VisualRuntimeBindingImplementationBinding.LifecycleEvent(
                        "bound",
                        stored.state(),
                        VisualRuntimeBindingImplementationBinding.STATE_BOUND,
                        "runtime-platform",
                        "repository-test",
                        "Approved.",
                        "Bind implementation.",
                        "",
                        now
                ),
                now
        );

        VisualRuntimeBindingImplementationBinding saved = repository.update(updated);

        assertThat(saved.revision()).isEqualTo(2);
        assertThat(saved.state()).isEqualTo("bound");
        assertThat(repository.findActiveBound("risk:eligibility")).contains(saved);

        DatabaseVisualRuntimeBindingImplementationRepository reloaded =
                new DatabaseVisualRuntimeBindingImplementationRepository(jdbc, objectMapper);
        reloaded.init();

        assertThat(reloaded.find("risk-binding-2")).contains(saved);
        assertThat(reloaded.findActiveBound("risk:eligibility")).contains(saved);
    }

    private static VisualRuntimeBindingImplementationBinding binding(String bindingId) {
        VisualRuntimeBindingImplementationValidation.ImplementationMetadata implementation =
                new VisualRuntimeBindingImplementationValidation.ImplementationMetadata(
                        bindingId,
                        "native",
                        "com.acme.risk.RiskEligibilityOperator",
                        "risk-platform",
                        List.of("request-response"),
                        List.of(new VisualRuntimeBindingImplementationValidation.Evidence(
                                "test", "golden-suite:risk", "Golden suite passed.")),
                        List.of(new VisualRuntimeBindingImplementationValidation.Evidence(
                                "approval", "change:42", "Approved.")),
                        "deployment:risk-v0",
                        ""
                );
        VisualRuntimeBindingHandoffBundle.OperatorContractSnapshot contract =
                new VisualRuntimeBindingHandoffBundle.OperatorContractSnapshot(
                        "risk:eligibility",
                        "1.0.0",
                        "sha256:contract",
                        "risk-policy-design",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                );
        VisualRuntimeBindingImplementationValidation.Request request =
                new VisualRuntimeBindingImplementationValidation.Request(
                        VisualRuntimeBindingImplementationValidation.REQUEST_SCHEMA_VERSION,
                        "risk:eligibility",
                        "sha256:contract",
                        "sha256:handoff",
                        List.of("RUNTIME_BINDING|draft|draft-1|node|executable-lowering|risk:eligibility|"),
                        contract,
                        implementation
                );
        VisualRuntimeBindingImplementationValidation validation =
                new VisualRuntimeBindingImplementationValidation(
                        VisualRuntimeBindingImplementationValidation.SCHEMA_VERSION,
                        null,
                        true,
                        true,
                        "ready-to-bind",
                        "success",
                        "Runtime binding implementation is ready to bind.",
                        request.operatorRef(),
                        request.operatorFingerprint(),
                        request.sourceHandoffBundleFingerprint(),
                        contract.fingerprint(),
                        "sha256:contract",
                        "current",
                        implementation,
                        List.of()
                );
        return VisualRuntimeBindingImplementationBinding.from(request, validation);
    }
}
