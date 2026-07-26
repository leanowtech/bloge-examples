package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;

/**
 * Durable selected-population admission result.
 *
 * @param schemaVersion exact response version
 * @param population complete verified immutable revision
 * @param idempotentReplay whether an exact existing revision was recovered
 */
public record AuthoritativeOutcomeSelectedPopulationAdmission(
        String schemaVersion,
        AuthoritativeOutcomeSelectedPopulationBundle population,
        boolean idempotentReplay
) {
    /** Current population admission response version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeSelectedPopulationAdmissionResult.v1";

    /** Requires one complete verified population bundle. */
    public AuthoritativeOutcomeSelectedPopulationAdmission {
        schemaVersion = schemaVersion == null
                || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported selected-population admission result schemaVersion");
        }
        population = Objects.requireNonNull(
                population, "population");
    }
}
