package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableWorkerQuarantineControlPlane;

import java.time.Instant;
import java.util.Objects;

/**
 * Token-free immutable receipt for one manual quarantine action.
 *
 * @param schemaVersion response protocol version
 * @param disposition newly resolved or idempotently replayed
 * @param key exact quarantine identity
 * @param ownerId verified maintenance actor
 * @param action release or discard
 * @param reasonCode bounded operational rationale
 * @param version resulting maintenance generation
 * @param actedAt database-clock action time
 * @param receiptFingerprint canonical immutable receipt fingerprint
 * @param idempotentReplay whether this is the original immutable command result
 */
public record DurableWorkerQuarantineResolutionResponse(
        String schemaVersion,
        String disposition,
        DurableWorkerQuarantineKey key,
        String ownerId,
        String action,
        String reasonCode,
        long version,
        Instant actedAt,
        String receiptFingerprint,
        boolean idempotentReplay) {
    /** Current resolution response protocol version. */
    public static final String SCHEMA_VERSION =
            "bloge.durableWorkerQuarantineResolutionResponse.v1";

    /** Validates a complete token-free action receipt. */
    public DurableWorkerQuarantineResolutionResponse {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        disposition = normalized(disposition);
        key = Objects.requireNonNull(key, "key");
        ownerId = normalized(ownerId);
        action = normalized(action);
        reasonCode = normalized(reasonCode);
        actedAt = Objects.requireNonNull(actedAt, "actedAt");
        receiptFingerprint = normalized(receiptFingerprint);
        if (disposition.isBlank() || ownerId.isBlank() || action.isBlank()
                || reasonCode.isBlank() || receiptFingerprint.isBlank() || version <= 0) {
            throw new IllegalArgumentException(
                    "A complete worker quarantine resolution is required");
        }
    }

    /** Creates a wire receipt from a successful control-plane result. */
    public static DurableWorkerQuarantineResolutionResponse from(
            DatabaseDurableWorkerQuarantineControlPlane.QuarantineResolutionResult result) {
        var receipt = result.receipt();
        return new DurableWorkerQuarantineResolutionResponse("", result.disposition().name(),
                new DurableWorkerQuarantineKey(
                        receipt.key().runId(), receipt.key().checkpointFingerprint()),
                receipt.ownerId(), receipt.action().name(), receipt.reasonCode(),
                receipt.version(), receipt.actedAt(), receipt.receiptFingerprint(),
                result.disposition() == DatabaseDurableWorkerQuarantineControlPlane
                        .ResolutionDisposition.IDEMPOTENT_REPLAY);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
