package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableStateProjectionControlPlane;

import java.time.Instant;
import java.util.Objects;

/**
 * Token-free immutable receipt for one manual projection finding resolution.
 *
 * @param schemaVersion response protocol version
 * @param disposition newly resolved or idempotently replayed
 * @param key resolved finding identity
 * @param ownerId verified operational owner
 * @param resolution manual resolution classification
 * @param version resulting finding revision
 * @param resolvedAt database-clock commit time
 * @param idempotentReplay whether this is the original immutable command result
 */
public record DurableStateProjectionFindingResolutionResponse(
        String schemaVersion,
        String disposition,
        DurableStateProjectionFindingKey key,
        String ownerId,
        String resolution,
        long version,
        Instant resolvedAt,
        boolean idempotentReplay) {
    /** Current resolution response protocol version. */
    public static final String SCHEMA_VERSION =
            "bloge.durableStateProjectionFindingResolutionResponse.v1";

    /** Validates a complete token-free resolution receipt. */
    public DurableStateProjectionFindingResolutionResponse {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        disposition = normalized(disposition);
        key = Objects.requireNonNull(key, "key");
        ownerId = normalized(ownerId);
        resolution = normalized(resolution);
        resolvedAt = Objects.requireNonNull(resolvedAt, "resolvedAt");
        if (ownerId.isBlank() || resolution.isBlank() || version <= 0) {
            throw new IllegalArgumentException(
                    "A complete projection finding resolution is required");
        }
    }

    /**
     * Creates the wire response from a committed or replayed control-plane result.
     *
     * @param result successful control-plane result
     * @return immutable token-free receipt
     */
    public static DurableStateProjectionFindingResolutionResponse from(
            DatabaseDurableStateProjectionControlPlane.FindingResolutionResult result) {
        DatabaseDurableStateProjectionControlPlane.FindingResolution resolution =
                result.resolution();
        return new DurableStateProjectionFindingResolutionResponse("",
                result.disposition().name(), new DurableStateProjectionFindingKey(
                resolution.key().entityType().name(), resolution.key().rowId()),
                resolution.ownerId(), resolution.resolution().name(), resolution.version(),
                resolution.resolvedAt(), result.disposition()
                == DatabaseDurableStateProjectionControlPlane.ResolutionDisposition
                .IDEMPOTENT_REPLAY);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
