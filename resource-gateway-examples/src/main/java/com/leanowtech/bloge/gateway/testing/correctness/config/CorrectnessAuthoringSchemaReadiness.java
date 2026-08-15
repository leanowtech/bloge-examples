package com.leanowtech.bloge.gateway.testing.correctness.config;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Objects;

/** Fails startup when an enabled Correctness runtime has not received its migrations. */
public final class CorrectnessAuthoringSchemaReadiness {

    private static final List<String> REQUIRED_TABLES = List.of(
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

    public CorrectnessAuthoringSchemaReadiness(JdbcTemplate jdbc) {
        Objects.requireNonNull(jdbc, "jdbc");
        for (String table : REQUIRED_TABLES) {
            try {
                jdbc.queryForList("SELECT 1 FROM " + table + " WHERE 1 = 0");
            } catch (RuntimeException missingOrUnauthorized) {
                throw new IllegalStateException(
                        "Correctness authoring schema is unavailable at table " + table
                                + "; apply V20260815_005 before enabling the runtime",
                        missingOrUnauthorized);
            }
        }
    }
}
