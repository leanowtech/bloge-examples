package com.leanowtech.bloge.gateway.testing.correctness.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.CorrectnessAuthoringRuntimeAvailability;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiService;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionService;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRegistryService;
import com.leanowtech.bloge.gateway.testing.correctness.coverage.CoverageInventoryService;
import com.leanowtech.bloge.gateway.testing.correctness.coverage.CoverageDerivationSource;
import com.leanowtech.bloge.gateway.testing.correctness.coverage.CoverageReviewAuthorizer;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureReviewAuthorizer;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureSchemaSource;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.BusinessOracleService;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.OracleBasisSource;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.OracleReviewAuthorizer;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.AssertionSetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.BusinessOracleRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.CoverageInventoryRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.ScenarioDraftSetV2Repository;
import com.leanowtech.bloge.gateway.testing.correctness.publication.CorrectnessPublicationRepository;
import com.leanowtech.bloge.gateway.testing.correctness.governance.CorrectnessGovernanceRepository;
import com.leanowtech.bloge.gateway.testing.correctness.run.CorrectnessEvidenceRepository;
import com.leanowtech.bloge.gateway.testing.correctness.scenario.ScenarioExternalReferenceSource;
import com.leanowtech.bloge.gateway.testing.correctness.scenario.ScenarioReviewAuthorizer;

import org.springframework.aop.support.AopUtils;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CorrectnessAuthoringCommandRuntimeConfigurationTest {

    private static final List<String> AUTHORING_TABLES = List.of(
            "rg_correctness_definition_heads",
            "rg_correctness_definition_revisions",
            "rg_coverage_inventory_heads",
            "rg_coverage_inventory_revisions",
            "rg_coverage_obligation_index",
            "rg_business_oracle_heads",
            "rg_business_oracle_revisions",
            "rg_assertion_set_heads",
            "rg_assertion_set_revisions",
            "rg_scenario_draft_set_v2_heads",
            "rg_scenario_draft_set_v2_revisions",
            "rg_scenario_case_v2_index",
            "rg_scenario_case_obligation_ref_index",
            "rg_fixture_asset_heads",
            "rg_fixture_asset_revisions",
            "rg_fixture_usage_index",
            "rg_correctness_publications",
            "rg_correctness_publication_attempts",
            "rg_correctness_publication_attempt_history",
            "rg_correctness_evidence_companions",
            "rg_outcome_calibration_proposals",
            "rg_correctness_governance_feedback",
            "rg_correctness_outbox",
            "rg_correctness_command_receipts");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withPropertyValues("gateway.testing.correctness.enabled=true")
            .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
            .withUserConfiguration(TransactionalCorrectnessRuntimeConfiguration.class)
            .withConfiguration(AutoConfigurations.of(
                    CorrectnessAuthoringRuntimeConfiguration.class,
                    CorrectnessAuthoringCommandRuntimeConfiguration.class));

    /** Enables the same class-based transaction proxy mode used by the application runtime. */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionalCorrectnessRuntimeConfiguration {
        @Bean
        PlatformTransactionManager transactionManager(JdbcTemplate jdbc) {
            return new DataSourceTransactionManager(jdbc.getDataSource());
        }
    }

    @Test
    void remainsReadOnlyWithoutEnterpriseGovernanceAuthorities() {
        runner.withBean(JdbcTemplate.class, () -> jdbc(false)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CorrectnessAuthoringRuntimeAvailability.class))
                    .isEqualTo(new CorrectnessAuthoringRuntimeAvailability(
                            true, false, false, false, false, false,
                            false, false, false, false, false, true, true));
        });
    }

    @Test
    void assemblesEveryCommandSurfaceOnlyWhenItsAuthoritiesAndVaultExist() {
        runner.withPropertyValues(
                        "gateway.testing.correctness.fixture-material.enabled=true",
                        "gateway.testing.correctness.fixture-material.active-key-id=demo",
                        "gateway.testing.correctness.fixture-material.key-ring="
                                + "demo=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
                .withBean(JdbcTemplate.class, () -> jdbc(true))
                .withBean(CoverageReviewAuthorizer.class,
                        () -> (scope, inventory, actor) -> true)
                .withBean(CoverageDerivationSource.class,
                        () -> (scope, target) -> null)
                .withBean(OracleReviewAuthorizer.class,
                        () -> (scope, oracle, actor) ->
                                OracleReviewAuthorizer.ApprovalDecision.ownerReview())
                .withBean(OracleBasisSource.class,
                        () -> (scope, target, refs) -> true)
                .withBean(ScenarioReviewAuthorizer.class,
                        () -> (scope, set, scenario, actor) ->
                                ScenarioReviewAuthorizer.ReviewDecision.governed())
                .withBean(ScenarioExternalReferenceSource.class,
                        () -> (scope, target, reference) -> true)
                .withBean(FixtureReviewAuthorizer.class,
                        () -> (scope, fixture, actor) ->
                                FixtureReviewAuthorizer.ApprovalDecision.ownerReview())
                .withBean(FixtureSchemaSource.class,
                        () -> (scope, schema) -> true)
                .withBean(TestExecutionApiService.class,
                        () -> mock(TestExecutionApiService.class))
                .withBean(TestSuiteRegistryService.class,
                        () -> mock(TestSuiteRegistryService.class))
                .withBean(TestSuiteExecutionService.class,
                        () -> mock(TestSuiteExecutionService.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CorrectnessAuthoringRuntimeAvailability.class))
                            .isEqualTo(new CorrectnessAuthoringRuntimeAvailability(
                                    true, true, true, true, true, true,
                                    true, true, true, true, true, true, true));
                    assertThat(context).hasSingleBean(
                            com.leanowtech.bloge.gateway.testing.correctness.run
                                    .CorrectnessPreflightFacade.class);
                    assertThat(context).hasSingleBean(
                            com.leanowtech.bloge.gateway.testing.correctness.run
                                    .CorrectnessRunService.class);
                    assertThat(context).hasSingleBean(
                            com.leanowtech.bloge.gateway.visual.authoring.application.fixture
                                    .FixtureSetShareMaterialWriter.class);
                    assertThat(context).hasSingleBean(
                            com.leanowtech.bloge.gateway.visual.authoring.application.fixture
                                    .FixtureSetReviewMaterialGate.class);
                    assertThat(AopUtils.isCglibProxy(
                            context.getBean(CoverageInventoryRepository.class))).isTrue();
                    assertThat(AopUtils.isCglibProxy(
                            context.getBean(BusinessOracleRepository.class))).isTrue();
                    assertThat(AopUtils.isCglibProxy(
                            context.getBean(AssertionSetRepository.class))).isTrue();
                    assertThat(AopUtils.isCglibProxy(
                            context.getBean(ScenarioDraftSetV2Repository.class))).isTrue();
                    assertThat(AopUtils.isCglibProxy(
                            context.getBean(CorrectnessPublicationRepository.class))).isTrue();
                    assertThat(AopUtils.isCglibProxy(
                            context.getBean(CorrectnessEvidenceRepository.class))).isTrue();
                    assertThat(AopUtils.isCglibProxy(
                            context.getBean(CorrectnessGovernanceRepository.class))).isTrue();
                    assertThat(AopUtils.isCglibProxy(
                            context.getBean(CoverageInventoryService.class))).isTrue();
                    assertThat(AopUtils.isCglibProxy(
                            context.getBean(BusinessOracleService.class))).isTrue();
                });
    }

    @Test
    void failsClosedWhenMaterialIsEnabledWithoutAnEncryptionKey() {
        runner.withPropertyValues(
                        "gateway.testing.correctness.fixture-material.enabled=true")
                .withBean(JdbcTemplate.class, () -> jdbc(true))
                .run(context -> assertThat(context).hasFailed());
    }

    private static JdbcTemplate jdbc(boolean material) {
        JdbcTemplate jdbc = new JdbcTemplate(new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build());
        AUTHORING_TABLES.forEach(table -> jdbc.execute("CREATE TABLE " + table
                + ("rg_correctness_publications".equals(table)
                ? " (id INTEGER, publication_attempt_id VARCHAR(512))" : " (id INTEGER)")));
        if (material) {
            jdbc.execute("CREATE TABLE rg_fixture_material_v2_revisions (id INTEGER)");
            jdbc.execute("CREATE TABLE rg_fixture_material_access_audit (id INTEGER)");
        }
        return jdbc;
    }
}
