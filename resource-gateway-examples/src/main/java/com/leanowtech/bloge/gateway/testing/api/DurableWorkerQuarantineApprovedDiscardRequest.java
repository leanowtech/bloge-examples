package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * Maker command that consumes an independent checker approval and discards a quarantine.
 *
 * @param schemaVersion request protocol version
 * @param clientRequestId caller-stable discard idempotency key
 * @param key exact quarantine identity
 * @param claimToken server-minted maker secret
 * @param claimVersion exact claimed maintenance generation
 * @param claimUntil exact database-clock claim deadline
 * @param approvalId independent checker approval identity
 * @param reasonCode rationale that must exactly match the approval
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record DurableWorkerQuarantineApprovedDiscardRequest(
        String schemaVersion,
        String clientRequestId,
        DurableWorkerQuarantineKey key,
        String claimToken,
        long claimVersion,
        Instant claimUntil,
        String approvalId,
        String reasonCode) {
    /** Current approved discard request protocol version. */
    public static final String SCHEMA_VERSION =
            "bloge.durableWorkerQuarantineApprovedDiscardRequest.v1";

    /** Applies the current version when omitted and normalizes textual fields. */
    public DurableWorkerQuarantineApprovedDiscardRequest {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        clientRequestId = normalized(clientRequestId);
        claimToken = normalized(claimToken);
        approvalId = normalized(approvalId);
        reasonCode = normalized(reasonCode);
    }

    /** Rejects caller-selected maker, checker, scope, audit, or future command fields. */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException(
                "Unknown approved worker quarantine discard field: " + field);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
