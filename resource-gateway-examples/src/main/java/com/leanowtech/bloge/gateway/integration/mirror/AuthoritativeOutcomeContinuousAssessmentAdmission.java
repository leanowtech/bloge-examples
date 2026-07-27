package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;

/**
 * Durable continuous-assessment registration result.
 *
 * @param schemaVersion exact response version
 * @param status database-observed effective projection status
 * @param idempotentReplay whether an exact existing registration was recovered
 */
public record AuthoritativeOutcomeContinuousAssessmentAdmission(
        String schemaVersion,
        AuthoritativeOutcomeContinuousAssessmentStatus status,
        boolean idempotentReplay
) {
    /** Current registration result version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeContinuousAssessmentAdmission.v1";

    /** Requires one exact effective status. */
    public AuthoritativeOutcomeContinuousAssessmentAdmission {
        schemaVersion = schemaVersion == null
                || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported continuous assessment admission schemaVersion");
        }
        status = Objects.requireNonNull(
                status, "status");
    }
}
