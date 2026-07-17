package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Caller intent for idempotently claiming one exact-checkpoint quarantine.
 *
 * <p>Scope and claim owner are deliberately absent; both come from verified workload identity.</p>
 *
 * @param schemaVersion request protocol version
 * @param clientRequestId caller-stable idempotency key
 * @param key exact quarantine identity
 * @param claimDurationSeconds database-clock lease duration
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record DurableWorkerQuarantineClaimRequest(
        String schemaVersion,
        String clientRequestId,
        DurableWorkerQuarantineKey key,
        long claimDurationSeconds) {
    /** Current claim request protocol version. */
    public static final String SCHEMA_VERSION = "bloge.durableWorkerQuarantineClaimRequest.v1";

    /** Applies the current version only when omitted and normalizes the request ID. */
    public DurableWorkerQuarantineClaimRequest {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        clientRequestId = normalized(clientRequestId);
    }

    /** Rejects caller-selected owner, scope, audit, or future command fields. */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unknown worker quarantine claim field: " + field);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
