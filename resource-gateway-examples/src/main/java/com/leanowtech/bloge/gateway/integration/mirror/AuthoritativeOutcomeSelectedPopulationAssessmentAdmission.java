package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;

/**
 * Durable completeness-assessment admission result.
 *
 * @param schemaVersion exact response version
 * @param assessment exact signed immutable assessment
 * @param predecessorFingerprint blank for revision one, exact predecessor otherwise
 * @param idempotentReplay whether an exact existing assessment was recovered
 */
public record
AuthoritativeOutcomeSelectedPopulationAssessmentAdmission(
        String schemaVersion,
        AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                assessment,
        String predecessorFingerprint,
        boolean idempotentReplay
) {
    /** Current completeness-assessment admission result version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeSelectedPopulationAssessmentAdmission.v1";

    /** Requires exact immutable assessment lineage. */
    public AuthoritativeOutcomeSelectedPopulationAssessmentAdmission {
        schemaVersion = schemaVersion == null
                || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported selected-population assessment admission schemaVersion");
        }
        assessment = Objects.requireNonNull(
                assessment, "assessment");
        predecessorFingerprint =
                predecessorFingerprint == null
                        ? "" : predecessorFingerprint.trim();
        if (!predecessorFingerprint.isBlank()
                && !predecessorFingerprint.matches(
                "sha256:[a-f0-9]{64}")
                || (assessment.revision() == 1)
                != predecessorFingerprint.isBlank()) {
            throw new IllegalArgumentException(
                    "selected-population assessment predecessor is invalid");
        }
    }
}
