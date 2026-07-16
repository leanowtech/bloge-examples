package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Caller intent for idempotently claiming one projection finding.
 *
 * <p>The claim owner is deliberately absent and is derived from verified workload identity.</p>
 *
 * @param schemaVersion claim request protocol version
 * @param clientRequestId caller-stable idempotency key
 * @param key payload-free finding identity
 * @param claimDurationSeconds requested database-clock lease duration
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record DurableStateProjectionFindingClaimRequest(
        String schemaVersion,
        String clientRequestId,
        DurableStateProjectionFindingKey key,
        long claimDurationSeconds) {
    /** Current claim request protocol version. */
    public static final String SCHEMA_VERSION = "bloge.durableStateProjectionFindingClaimRequest.v1";

    /** Applies the current version only when the caller omitted it. */
    public DurableStateProjectionFindingClaimRequest {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        clientRequestId = normalized(clientRequestId);
    }

    /**
     * Rejects caller-selected owner, audit, or future command fields.
     *
     * @param field unknown JSON field
     * @param value ignored caller value
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unknown projection finding claim field: " + field);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
