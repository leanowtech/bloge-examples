package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Caller intent for renewing one exact issued durable-recovery fence.
 *
 * <p>The internal dispatch, lease duration, authorization receipt, and successor identity are
 * deliberately absent. The service resolves the unique committed dispatch from this exact fence
 * and applies deployment-owned lease policy.</p>
 *
 * @param schemaVersion heartbeat request protocol version
 * @param clientRequestId caller-stable idempotency key
 * @param expectedFence exact owner, epoch, and revision from the previous response
 * @param expectedCheckpointFingerprint exact previous control-checkpoint identity
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record DurableTestRecoveryHeartbeatRequest(
        String schemaVersion,
        String clientRequestId,
        Fence expectedFence,
        String expectedCheckpointFingerprint
) {
    /** Current public recovery-heartbeat request protocol. */
    public static final String SCHEMA_VERSION =
            "bloge.durableTestRecoveryHeartbeatRequest.v1";

    /** Applies the default version and normalizes caller-controlled identifiers. */
    public DurableTestRecoveryHeartbeatRequest {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        clientRequestId = normalized(clientRequestId);
        expectedCheckpointFingerprint = normalized(expectedCheckpointFingerprint);
    }

    /**
     * Rejects caller-owned lease, dispatch, or future control fields.
     *
     * @param field unknown JSON property name
     * @param value ignored caller-supplied value
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unknown durable recovery-heartbeat field: " + field);
    }

    /**
     * Exact previously observed recovery fence.
     *
     * @param ownerId server-selected recovery owner
     * @param leaseEpoch positive ownership generation
     * @param revision non-negative control revision
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Fence(String ownerId, long leaseEpoch, long revision) {
        /** Normalizes the server-issued owner identifier. */
        public Fence {
            ownerId = normalized(ownerId);
        }

        /**
         * Rejects ambiguous future fence fields.
         *
         * @param field unknown JSON property name
         * @param value ignored caller-supplied value
         */
        @JsonAnySetter
        public void rejectUnknownField(String field, Object value) {
            throw new IllegalArgumentException(
                    "Unknown durable recovery-heartbeat fence field: " + field);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
