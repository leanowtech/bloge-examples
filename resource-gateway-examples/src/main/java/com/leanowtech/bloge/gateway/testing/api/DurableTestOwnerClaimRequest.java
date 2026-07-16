package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAnySetter;

/**
 * Caller intent for taking ownership of one expired durable test execution.
 *
 * <p>The claimant owner and lease duration are intentionally absent. They are deployment policy,
 * not caller authority, and are supplied only by the server-side recovery worker configuration.</p>
 *
 * @param schemaVersion owner-claim request protocol version
 * @param clientRequestId caller-stable idempotency key
 * @param expectedFence exact previously observed owner, epoch, and revision
 * @param expectedCheckpointFingerprint exact checkpoint closure previously observed by the caller
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record DurableTestOwnerClaimRequest(
        String schemaVersion,
        String clientRequestId,
        Fence expectedFence,
        String expectedCheckpointFingerprint
) {
    /** Current owner-claim request protocol version. */
    public static final String SCHEMA_VERSION = "bloge.durableTestOwnerClaimRequest.v1";

    /** Applies the current protocol version while preserving invalid explicit versions for validation. */
    public DurableTestOwnerClaimRequest {
        schemaVersion = normalized(schemaVersion).isBlank() ? SCHEMA_VERSION : normalized(schemaVersion);
        clientRequestId = normalized(clientRequestId);
        expectedCheckpointFingerprint = normalized(expectedCheckpointFingerprint);
    }

    /**
     * Rejects caller attempts to supply server-owned or future command fields.
     *
     * @param field unknown JSON property name
     * @param value ignored caller-supplied value
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unknown durable owner-claim field: " + field);
    }

    /**
     * Exact prior ownership fence selected by the caller.
     *
     * @param ownerId previously observed owner
     * @param leaseEpoch previously observed positive lease generation
     * @param revision previously observed non-negative checkpoint revision
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Fence(String ownerId, long leaseEpoch, long revision) {
        /** Normalizes the bounded owner identifier; service validation owns error translation. */
        public Fence {
            ownerId = normalized(ownerId);
        }

        /**
         * Rejects ambiguous future fence fields instead of silently weakening compare-and-set.
         *
         * @param field unknown JSON property name
         * @param value ignored caller-supplied value
         */
        @JsonAnySetter
        public void rejectUnknownField(String field, Object value) {
            throw new IllegalArgumentException("Unknown durable owner-claim fence field: " + field);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
