package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * Exact maintenance claim fence and manual quarantine action.
 *
 * @param schemaVersion request protocol version
 * @param clientRequestId caller-stable idempotency key
 * @param key exact quarantine identity
 * @param claimToken server-minted claim secret
 * @param claimVersion exact claimed maintenance generation
 * @param claimUntil exact database-clock deadline returned by claim
 * @param action {@code RELEASE} or {@code DISCARD}
 * @param reasonCode bounded non-payload operational rationale
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record DurableWorkerQuarantineResolutionRequest(
        String schemaVersion,
        String clientRequestId,
        DurableWorkerQuarantineKey key,
        String claimToken,
        long claimVersion,
        Instant claimUntil,
        String action,
        String reasonCode) {
    /** Current resolution request protocol version. */
    public static final String SCHEMA_VERSION =
            "bloge.durableWorkerQuarantineResolutionRequest.v1";

    /** Applies the current version only when omitted and normalizes protocol text. */
    public DurableWorkerQuarantineResolutionRequest {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        clientRequestId = normalized(clientRequestId);
        claimToken = normalized(claimToken);
        action = normalized(action);
        reasonCode = normalized(reasonCode);
    }

    /** Rejects caller-selected owner, scope, audit, or future command fields. */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException(
                "Unknown worker quarantine resolution field: " + field);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
