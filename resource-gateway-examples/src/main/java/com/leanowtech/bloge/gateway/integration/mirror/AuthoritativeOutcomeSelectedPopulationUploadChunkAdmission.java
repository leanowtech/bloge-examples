package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;

/**
 * Public idempotent result of staging one population chunk.
 *
 * @param schemaVersion exact result protocol version
 * @param status durable payload-free upload status
 * @param chunkIndex exact manifest chunk index
 * @param chunkFingerprint canonical staged chunk address
 * @param idempotentReplay whether the exact chunk was already staged
 */
public record AuthoritativeOutcomeSelectedPopulationUploadChunkAdmission(
        String schemaVersion,
        AuthoritativeOutcomeSelectedPopulationUploadStatus status,
        int chunkIndex,
        String chunkFingerprint,
        boolean idempotentReplay
) {
    /** Current staged-chunk result version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeSelectedPopulationUploadChunkAdmission.v1";

    /** Requires one exact durable chunk result. */
    public AuthoritativeOutcomeSelectedPopulationUploadChunkAdmission {
        schemaVersion = schemaVersion == null
                || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || chunkIndex < 0
                || chunkFingerprint == null
                || chunkFingerprint.isBlank()) {
            throw new IllegalArgumentException(
                    "selected-population upload chunk admission is invalid");
        }
        status = Objects.requireNonNull(
                status, "status");
    }

    /** Converts an internal durable chunk result to the public wire result. */
    public static
    AuthoritativeOutcomeSelectedPopulationUploadChunkAdmission
    from(
            AuthoritativeOutcomeSelectedPopulationUploadRepository
                    .ChunkAdmission admission) {
        AuthoritativeOutcomeSelectedPopulationUploadRepository
                .ChunkAdmission exact =
                Objects.requireNonNull(
                        admission, "admission");
        return new
                AuthoritativeOutcomeSelectedPopulationUploadChunkAdmission(
                "",
                exact.status(),
                exact.chunkIndex(),
                exact.chunkFingerprint(),
                exact.idempotentReplay());
    }
}
