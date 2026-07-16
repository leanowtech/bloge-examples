package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableStateProjectionControlPlane;

import java.time.Instant;
import java.util.Objects;

/**
 * Exact server-issued claim fence; the token appears only in this successful command response.
 *
 * @param schemaVersion response protocol version
 * @param disposition newly claimed or idempotently replayed
 * @param key claimed finding identity
 * @param ownerId verified workload actor selected by the server
 * @param claimToken unguessable resolution fence
 * @param version exact claimed finding revision
 * @param claimUntil database-clock claim deadline
 * @param idempotentReplay whether this is the original immutable command result
 */
public record DurableStateProjectionFindingClaimResponse(
        String schemaVersion,
        String disposition,
        DurableStateProjectionFindingKey key,
        String ownerId,
        String claimToken,
        long version,
        Instant claimUntil,
        boolean idempotentReplay) {
    /** Current claim response protocol version. */
    public static final String SCHEMA_VERSION = "bloge.durableStateProjectionFindingClaimResponse.v1";

    /** Validates a complete successful claim fence. */
    public DurableStateProjectionFindingClaimResponse {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        disposition = normalized(disposition);
        key = Objects.requireNonNull(key, "key");
        ownerId = normalized(ownerId);
        claimToken = normalized(claimToken);
        claimUntil = Objects.requireNonNull(claimUntil, "claimUntil");
        if (ownerId.isBlank() || claimToken.isBlank() || version <= 0) {
            throw new IllegalArgumentException("A complete projection finding claim is required");
        }
    }

    /**
     * Creates the wire response from a committed or replayed control-plane result.
     *
     * @param result successful control-plane result
     * @return complete server-issued fence
     */
    public static DurableStateProjectionFindingClaimResponse from(
            DatabaseDurableStateProjectionControlPlane.FindingClaimResult result) {
        DatabaseDurableStateProjectionControlPlane.FindingClaim claim = result.claim();
        return new DurableStateProjectionFindingClaimResponse("", result.disposition().name(),
                new DurableStateProjectionFindingKey(
                        claim.key().entityType().name(), claim.key().rowId()),
                claim.ownerId(), claim.claimToken(), claim.version(), claim.claimUntil(),
                result.disposition()
                        == DatabaseDurableStateProjectionControlPlane.ClaimDisposition
                        .IDEMPOTENT_REPLAY);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
