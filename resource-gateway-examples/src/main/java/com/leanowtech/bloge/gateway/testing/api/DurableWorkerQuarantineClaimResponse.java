package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableWorkerQuarantineControlPlane;

import java.time.Instant;
import java.util.Objects;

/**
 * Exact server-issued quarantine claim fence.
 *
 * @param schemaVersion response protocol version
 * @param disposition newly claimed or idempotently replayed
 * @param key exact quarantine identity
 * @param ownerId verified workload actor selected by the server
 * @param claimToken unguessable response-only resolution fence
 * @param version exact maintenance generation
 * @param claimUntil database-clock lease deadline
 * @param idempotentReplay whether this is an immutable command replay
 */
public record DurableWorkerQuarantineClaimResponse(
        String schemaVersion,
        String disposition,
        DurableWorkerQuarantineKey key,
        String ownerId,
        String claimToken,
        long version,
        Instant claimUntil,
        boolean idempotentReplay) {
    /** Current claim response protocol version. */
    public static final String SCHEMA_VERSION = "bloge.durableWorkerQuarantineClaimResponse.v1";

    /** Validates a complete successful claim fence. */
    public DurableWorkerQuarantineClaimResponse {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        disposition = normalized(disposition);
        key = Objects.requireNonNull(key, "key");
        ownerId = normalized(ownerId);
        claimToken = normalized(claimToken);
        claimUntil = Objects.requireNonNull(claimUntil, "claimUntil");
        if (disposition.isBlank() || ownerId.isBlank() || claimToken.isBlank()
                || version <= 0) {
            throw new IllegalArgumentException("A complete worker quarantine claim is required");
        }
    }

    /** Creates a wire response from a successful control-plane result. */
    public static DurableWorkerQuarantineClaimResponse from(
            DatabaseDurableWorkerQuarantineControlPlane.QuarantineClaimResult result) {
        DatabaseDurableWorkerQuarantineControlPlane.QuarantineClaim claim = result.claim();
        return new DurableWorkerQuarantineClaimResponse("", result.disposition().name(),
                new DurableWorkerQuarantineKey(
                        claim.key().runId(), claim.key().checkpointFingerprint()),
                claim.ownerId(), claim.claimToken(), claim.version(), claim.claimUntil(),
                result.disposition() == DatabaseDurableWorkerQuarantineControlPlane
                        .ClaimDisposition.IDEMPOTENT_REPLAY);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
