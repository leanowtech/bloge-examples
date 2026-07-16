package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * Exact claim fence and manual disposition for resolving one projection finding.
 *
 * @param schemaVersion resolution request protocol version
 * @param clientRequestId caller-stable idempotency key
 * @param key payload-free finding identity
 * @param claimToken server-minted claim secret
 * @param claimVersion exact claimed finding revision
 * @param claimUntil exact database-clock deadline returned by claim
 * @param resolution {@code MANUALLY_REPAIRED} or {@code QUARANTINED}
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record DurableStateProjectionFindingResolutionRequest(
        String schemaVersion,
        String clientRequestId,
        DurableStateProjectionFindingKey key,
        String claimToken,
        long claimVersion,
        Instant claimUntil,
        String resolution) {
    /** Current resolution request protocol version. */
    public static final String SCHEMA_VERSION =
            "bloge.durableStateProjectionFindingResolutionRequest.v1";

    /** Applies the current version only when omitted and normalizes protocol text. */
    public DurableStateProjectionFindingResolutionRequest {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        clientRequestId = normalized(clientRequestId);
        claimToken = normalized(claimToken);
        resolution = normalized(resolution);
    }

    /**
     * Rejects caller-selected owner, audit, or future command fields.
     *
     * @param field unknown JSON field
     * @param value ignored caller value
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unknown projection finding resolution field: " + field);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
