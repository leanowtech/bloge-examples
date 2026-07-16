package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Caller intent for server-owned execution of one terminal cold-signal recovery.
 *
 * <p>The caller supplies only the exact current fence and the business signal. Outcome, engine
 * state, fixture cursor, provider state, evidence gaps, and terminal checkpoint are server-owned.</p>
 *
 * @param schemaVersion terminal-recovery request protocol version
 * @param clientRequestId caller-stable idempotency key
 * @param expectedFence exact current recovery fence
 * @param expectedCheckpointFingerprint exact current checkpoint identity
 * @param signal bounded business signal applied to the suspended BLOGE node
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record DurableTestTerminalRecoveryRequest(
        String schemaVersion,
        String clientRequestId,
        Fence expectedFence,
        String expectedCheckpointFingerprint,
        Signal signal
) {
    /** Current terminal-recovery request protocol. */
    public static final String SCHEMA_VERSION =
            "bloge.durableTestTerminalRecoveryRequest.v1";

    /** Applies the default version and normalizes caller-controlled identifiers. */
    public DurableTestTerminalRecoveryRequest {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        clientRequestId = normalized(clientRequestId);
        expectedCheckpointFingerprint = normalized(expectedCheckpointFingerprint);
    }

    /**
     * Rejects caller-owned terminal state or future control fields.
     *
     * @param field unknown JSON field name
     * @param value decoded unknown value, which is never consumed
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException(
                "Unknown durable terminal-recovery field: " + field);
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
         * Rejects caller-owned expiry, dispatch, or future fence fields.
         *
         * @param field unknown JSON field name
         * @param value decoded unknown value, which is never consumed
         */
        @JsonAnySetter
        public void rejectUnknownField(String field, Object value) {
            throw new IllegalArgumentException(
                    "Unknown durable terminal-recovery fence field: " + field);
        }
    }

    /**
     * One bounded signal delivered only to the isolated recovery engine.
     *
     * @param nodeId exact suspended signal node
     * @param data arbitrary JSON signal value; callers use an explicit JSON null for no value
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Signal(String nodeId, JsonNode data) {
        /** Normalizes the node and rejects an omitted signal value. */
        public Signal {
            nodeId = normalized(nodeId);
            if (data == null) {
                throw new IllegalArgumentException(
                        "Recovery signal data is required; use an explicit JSON null for no value");
            }
        }

        /**
         * Rejects caller-owned signal metadata or future execution controls.
         *
         * @param field unknown JSON field name
         * @param value decoded unknown value, which is never consumed
         */
        @JsonAnySetter
        public void rejectUnknownField(String field, Object value) {
            throw new IllegalArgumentException(
                    "Unknown durable terminal-recovery signal field: " + field);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
