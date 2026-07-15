package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;

/**
 * Scoped replay-payload registry projection. A tombstone retains immutable lineage after value
 * expiry so a failed suite can explain why an exact reference is no longer executable.
 *
 * @param schemaVersion stored projection protocol version
 * @param tenantId verified tenant scope
 * @param environmentId verified test-runtime environment scope
 * @param descriptor immutable payload descriptor
 * @param state {@code AVAILABLE}, {@code EXPIRED}, or {@code PURGED}
 * @param payloadAvailable distinguishes an available JSON null from a tombstone
 * @param value sanitized replay output when available
 * @param storedAt server persistence time
 * @param storedBy verified actor id
 */
public record StoredReplayPayload(
        String schemaVersion,
        String tenantId,
        String environmentId,
        ReplayPayloadDescriptor descriptor,
        String state,
        boolean payloadAvailable,
        Object value,
        Instant storedAt,
        String storedBy
) {
    /** Current stored replay-payload projection version. */
    public static final String SCHEMA_VERSION = "bloge.storedReplayPayload.v1";
    /** Payload value is present and may be resolved after authorization. */
    public static final String AVAILABLE = "AVAILABLE";
    /** Retention elapsed and the value was physically removed. */
    public static final String EXPIRED = "EXPIRED";
    /** An administrative purge removed the value before retention elapsed. */
    public static final String PURGED = "PURGED";

    /** Normalizes immutable scope and lifecycle facts. */
    public StoredReplayPayload {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        tenantId = normalized(tenantId);
        environmentId = normalized(environmentId);
        state = defaulted(state, AVAILABLE).toUpperCase(java.util.Locale.ROOT);
        storedAt = storedAt == null ? Instant.EPOCH : storedAt;
        storedBy = normalized(storedBy);
    }

    /**
     * Checks both lifecycle state and physical value availability.
     *
     * @return whether the governed value remains readable
     */
    public boolean readable() {
        return AVAILABLE.equals(state) && payloadAvailable;
    }

    /**
     * Removes the value while preserving the exact immutable descriptor and storage audit facts.
     *
     * @return payload-free tombstone preserving immutable lineage
     */
    public StoredReplayPayload expired() {
        return new StoredReplayPayload(schemaVersion, tenantId, environmentId, descriptor,
                EXPIRED, false, null, storedAt, storedBy);
    }

    private static String defaulted(String value, String fallback) {
        String normalized = normalized(value);
        return normalized.isBlank() ? fallback : normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
