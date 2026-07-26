package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;

/**
 * Public idempotent result of creating one selected-population upload.
 *
 * @param schemaVersion exact result protocol version
 * @param status durable payload-free upload status
 * @param idempotentReplay whether the same immutable intent already existed
 */
public record AuthoritativeOutcomeSelectedPopulationUploadAdmission(
        String schemaVersion,
        AuthoritativeOutcomeSelectedPopulationUploadStatus status,
        boolean idempotentReplay
) {
    /** Current upload-admission result version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeSelectedPopulationUploadAdmission.v1";

    /** Requires one concrete durable upload status. */
    public AuthoritativeOutcomeSelectedPopulationUploadAdmission {
        schemaVersion = schemaVersion == null
                || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported selected-population upload admission schemaVersion");
        }
        status = Objects.requireNonNull(
                status, "status");
    }

    /** Converts an internal durable admission to the public wire result. */
    public static AuthoritativeOutcomeSelectedPopulationUploadAdmission
    from(
            AuthoritativeOutcomeSelectedPopulationUploadRepository.Admission
                    admission) {
        AuthoritativeOutcomeSelectedPopulationUploadRepository.Admission
                exact = Objects.requireNonNull(
                admission, "admission");
        return new
                AuthoritativeOutcomeSelectedPopulationUploadAdmission(
                "",
                exact.status(),
                exact.idempotentReplay());
    }
}
