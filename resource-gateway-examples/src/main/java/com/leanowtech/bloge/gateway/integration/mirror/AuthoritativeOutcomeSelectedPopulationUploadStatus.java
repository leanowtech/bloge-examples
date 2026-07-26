package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Payload-free resumable status of one selected-population upload.
 *
 * @param schemaVersion exact status protocol version
 * @param uploadId stable upload identity
 * @param requestFingerprint immutable upload-intent address
 * @param populationId target population identity
 * @param populationRevision target immutable revision
 * @param state closed upload lifecycle state
 * @param expectedChunkCount manifest-declared chunk count
 * @param receivedChunkCount durably staged unique chunk count
 * @param receivedBytes durably staged encoded bytes
 * @param nextMissingChunkIndex lowest missing index, or {@code -1} when complete
 * @param finalizeEpoch monotonic finalizer fencing epoch
 * @param createdAt database creation time
 * @param updatedAt database last-transition time
 * @param expiresAt incomplete-upload expiration time
 * @param finalizeLeaseUntil current finalizer lease end, or epoch when not finalizing
 * @param finalizedPopulationFingerprint terminal population root address, or blank
 */
public record AuthoritativeOutcomeSelectedPopulationUploadStatus(
        String schemaVersion,
        String uploadId,
        String requestFingerprint,
        String populationId,
        long populationRevision,
        State state,
        int expectedChunkCount,
        int receivedChunkCount,
        long receivedBytes,
        int nextMissingChunkIndex,
        long finalizeEpoch,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt,
        Instant finalizeLeaseUntil,
        String finalizedPopulationFingerprint
) {
    /** Current payload-free upload status version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeSelectedPopulationUploadStatus.v1";
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");

    /** Enforces lifecycle, progress, and terminal-coordinate consistency. */
    public AuthoritativeOutcomeSelectedPopulationUploadStatus {
        schemaVersion = schemaVersion == null
                || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported selected-population upload status schemaVersion");
        }
        uploadId = required(uploadId, "uploadId");
        requestFingerprint = fingerprint(
                requestFingerprint, "requestFingerprint");
        populationId = required(
                populationId, "populationId");
        state = Objects.requireNonNull(state, "state");
        createdAt = Objects.requireNonNull(
                createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(
                updatedAt, "updatedAt");
        expiresAt = Objects.requireNonNull(
                expiresAt, "expiresAt");
        finalizeLeaseUntil = finalizeLeaseUntil == null
                ? Instant.EPOCH : finalizeLeaseUntil;
        finalizedPopulationFingerprint =
                finalizedPopulationFingerprint == null
                        ? ""
                        : finalizedPopulationFingerprint.trim();
        if (populationRevision < 1
                || expectedChunkCount < 1
                || receivedChunkCount < 0
                || receivedChunkCount > expectedChunkCount
                || receivedBytes < 0
                || finalizeEpoch < 0
                || updatedAt.isBefore(createdAt)
                || expiresAt.isBefore(createdAt)
                || nextMissingChunkIndex < -1
                || nextMissingChunkIndex
                >= expectedChunkCount
                || (receivedChunkCount == expectedChunkCount)
                != (nextMissingChunkIndex == -1)
                || (state == State.FINALIZING)
                != !finalizeLeaseUntil.equals(
                Instant.EPOCH)
                || (state == State.FINALIZED)
                != !finalizedPopulationFingerprint.isBlank()
                || !finalizedPopulationFingerprint.isBlank()
                && !FINGERPRINT.matcher(
                finalizedPopulationFingerprint).matches()) {
            throw new IllegalArgumentException(
                    "selected-population upload status is inconsistent");
        }
    }

    /** @return whether all manifest-declared chunks are durably staged */
    public boolean complete() {
        return nextMissingChunkIndex == -1;
    }

    /** Closed lifecycle vocabulary for one upload intent. */
    public enum State {
        /** Chunks may be staged or replayed. */
        OPEN,
        /** One fenced owner is verifying and committing the population. */
        FINALIZING,
        /** The population admission committed and can be replayed. */
        FINALIZED,
        /** The selection authority explicitly abandoned the upload. */
        ABORTED,
        /** The incomplete upload exceeded its server-owned lifetime. */
        EXPIRED
    }

    private static String required(
            String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (exact.isBlank()) {
            throw new IllegalArgumentException(
                    field + " is required");
        }
        return exact;
    }

    private static String fingerprint(
            String value, String field) {
        String exact = required(value, field);
        if (!FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return exact;
    }
}
