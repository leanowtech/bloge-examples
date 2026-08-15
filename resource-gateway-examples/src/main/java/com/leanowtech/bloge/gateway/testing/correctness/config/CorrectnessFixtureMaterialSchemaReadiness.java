package com.leanowtech.bloge.gateway.testing.correctness.config;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Objects;

/** Separate migration gate for the encrypted Fixture material vault. */
public final class CorrectnessFixtureMaterialSchemaReadiness {

    public CorrectnessFixtureMaterialSchemaReadiness(JdbcTemplate jdbc) {
        Objects.requireNonNull(jdbc, "jdbc");
        for (String table : List.of(
                "rg_fixture_material_v2_revisions",
                "rg_fixture_material_access_audit")) {
            try {
                jdbc.queryForList("SELECT 1 FROM " + table + " WHERE 1 = 0");
            } catch (RuntimeException missingOrUnauthorized) {
                throw new IllegalStateException(
                        "Fixture material schema is unavailable at table " + table
                                + "; apply V20260815_006 before enabling material access",
                        missingOrUnauthorized);
            }
        }
    }
}
