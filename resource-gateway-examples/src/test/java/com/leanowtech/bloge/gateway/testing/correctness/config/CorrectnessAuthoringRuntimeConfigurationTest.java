package com.leanowtech.bloge.gateway.testing.correctness.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.CorrectnessAuthoringRuntimeAvailability;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceQuery;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CorrectnessAuthoringRuntimeConfigurationTest {

    private static final List<String> TABLES = List.of(
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
            "rg_correctness_outbox",
            "rg_correctness_command_receipts");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                    CorrectnessAuthoringRuntimeConfiguration.class));

    @Test
    void remainsPhysicallyAbsentWhenFeatureIsDisabled() {
        runner.withBean(JdbcTemplate.class, CorrectnessAuthoringRuntimeConfigurationTest::jdbc)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CorrectnessWorkspaceQuery.class);
                    assertThat(context).doesNotHaveBean(
                            CorrectnessAuthoringRuntimeAvailability.class);
                });
    }

    @Test
    void failsStartupWhenAnEnabledDeploymentSkippedItsMigration() {
        runner.withPropertyValues("gateway.testing.correctness.enabled=true")
                .withBean(JdbcTemplate.class, CorrectnessAuthoringRuntimeConfigurationTest::jdbc)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failsStartupWhenPublicationTraceabilityMigrationWasSkipped() {
        runner.withPropertyValues("gateway.testing.correctness.enabled=true")
                .withBean(JdbcTemplate.class, () -> {
                    JdbcTemplate jdbc = jdbc();
                    TABLES.forEach(table ->
                            jdbc.execute("CREATE TABLE " + table + " (id INTEGER)"));
                    return jdbc;
                })
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("publication_attempt_id");
                });
    }

    @Test
    void assemblesTheFullMetadataOnlyWorkspaceAfterSchemaReadiness() {
        runner.withPropertyValues("gateway.testing.correctness.enabled=true")
                .withBean(JdbcTemplate.class, () -> jdbcWithReadinessTables())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CorrectnessWorkspaceQuery.class);
                    assertThat(context.getBean(CorrectnessAuthoringRuntimeAvailability.class))
                            .isEqualTo(new CorrectnessAuthoringRuntimeAvailability(
                                    true, false, false, false, false, false,
                                    false, false, false));
                });
    }

    private static JdbcTemplate jdbcWithReadinessTables() {
        JdbcTemplate jdbc = jdbc();
        TABLES.forEach(table -> jdbc.execute("CREATE TABLE " + table
                + ("rg_correctness_publications".equals(table)
                ? " (id INTEGER, publication_attempt_id VARCHAR(512))" : " (id INTEGER)")));
        return jdbc;
    }

    private static JdbcTemplate jdbc() {
        return new JdbcTemplate(new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build());
    }
}
