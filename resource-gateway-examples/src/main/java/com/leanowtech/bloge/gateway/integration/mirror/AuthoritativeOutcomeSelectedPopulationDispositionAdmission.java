package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;

/**
 * Durable legal-disposition admission result.
 *
 * @param schemaVersion exact response version
 * @param disposition exact signed immutable revision
 * @param predecessorFingerprint blank for revision one, exact predecessor otherwise
 * @param idempotentReplay whether an exact existing revision was recovered
 */
public record
AuthoritativeOutcomeSelectedPopulationDispositionAdmission(
        String schemaVersion,
        AuthoritativeOutcomeSelectedPopulationDisposition
                disposition,
        String predecessorFingerprint,
        boolean idempotentReplay
) {
    /** Current legal-disposition admission result version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeSelectedPopulationDispositionAdmissionResult.v1";

    /** Requires exact immutable disposition lineage. */
    public AuthoritativeOutcomeSelectedPopulationDispositionAdmission {
        schemaVersion = schemaVersion == null
                || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported selected-member disposition result schemaVersion");
        }
        disposition = Objects.requireNonNull(
                disposition, "disposition");
        predecessorFingerprint =
                predecessorFingerprint == null
                        ? "" : predecessorFingerprint.trim();
        if (!predecessorFingerprint.isBlank()
                && !predecessorFingerprint.matches(
                "sha256:[a-f0-9]{64}")
                || (disposition.revision() == 1)
                != predecessorFingerprint.isBlank()) {
            throw new IllegalArgumentException(
                    "selected-member disposition result predecessor is invalid");
        }
    }
}
