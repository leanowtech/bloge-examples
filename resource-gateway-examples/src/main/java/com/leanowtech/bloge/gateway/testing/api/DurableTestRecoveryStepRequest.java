package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Caller intent for advancing one issued durable recovery dispatch by exactly one signal.
 *
 * <p>The caller owns only the idempotency key, previously observed fence and checkpoint identity,
 * and business signal. The server derives the next BLOGE boundary, engine state, fixture cursor,
 * provider state, evidence gaps, lease transition, and optional terminal receipt.</p>
 *
 * @param schemaVersion recovery-step request protocol version
 * @param clientRequestId caller-stable idempotency key
 * @param expectedFence exact current recovery fence
 * @param expectedCheckpointFingerprint exact current checkpoint identity
 * @param signal bounded business signal applied to the suspended BLOGE node
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record DurableTestRecoveryStepRequest(
        String schemaVersion,
        String clientRequestId,
        Fence expectedFence,
        String expectedCheckpointFingerprint,
        Signal signal
) {
    /** Current one-signal recovery-step request protocol. */
    public static final String SCHEMA_VERSION =
            "bloge.durableTestRecoveryStepRequest.v1";

    /** Applies the default version and normalizes caller-controlled identifiers. */
    public DurableTestRecoveryStepRequest {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        clientRequestId = normalized(clientRequestId);
        expectedCheckpointFingerprint = normalized(expectedCheckpointFingerprint);
    }

    /** Rejects caller-owned outcome, engine state, lease, evidence, and future fields. */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException(
                "Unknown durable recovery-step field: " + field);
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

        /** Rejects caller-owned expiry, dispatch, and future fence fields. */
        @JsonAnySetter
        public void rejectUnknownField(String field, Object value) {
            throw new IllegalArgumentException(
                    "Unknown durable recovery-step fence field: " + field);
        }
    }

    /**
     * One bounded signal delivered only to the isolated recovery engine.
     *
     * @param nodeId exact suspended signal node
     * @param data arbitrary JSON signal value; use explicit JSON null for no value
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

        /** Rejects caller-owned signal metadata and future execution controls. */
        @JsonAnySetter
        public void rejectUnknownField(String field, Object value) {
            throw new IllegalArgumentException(
                    "Unknown durable recovery-step signal field: " + field);
        }
    }

    /**
     * Adapts caller-owned fields to the shared dispatch and authorization checks.
     *
     * @return field-equivalent terminal-intent view; no terminal policy is implied
     */
    DurableTestTerminalRecoveryRequest sharedControlIntent() {
        if (expectedFence == null || signal == null) {
            return new DurableTestTerminalRecoveryRequest(
                    "", clientRequestId, null, expectedCheckpointFingerprint, null);
        }
        return new DurableTestTerminalRecoveryRequest(
                "", clientRequestId,
                new DurableTestTerminalRecoveryRequest.Fence(
                        expectedFence.ownerId(), expectedFence.leaseEpoch(),
                        expectedFence.revision()),
                expectedCheckpointFingerprint,
                new DurableTestTerminalRecoveryRequest.Signal(signal.nodeId(), signal.data()));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
