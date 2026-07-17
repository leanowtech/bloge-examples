package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Caller intent for automatically advancing a durable run through a bounded signal sequence.
 *
 * <p>The complete ordered signal program is fingerprinted and reserved before its first child
 * step executes. The server owns every intermediate claim, child idempotency key, dispatch,
 * engine state, lease, and resulting boundary.</p>
 *
 * @param schemaVersion recovery-sequence request protocol version
 * @param clientRequestId caller-stable idempotency key for the complete ordered program
 * @param expectedFence exact fence for the first signal
 * @param expectedCheckpointFingerprint exact checkpoint identity for the first signal
 * @param signals one to sixteen ordered signals consumed until terminal or exhaustion
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record DurableTestRecoverySequenceRequest(
        String schemaVersion,
        String clientRequestId,
        Fence expectedFence,
        String expectedCheckpointFingerprint,
        List<Signal> signals
) {
    /** Current bounded recovery-sequence request protocol. */
    public static final String SCHEMA_VERSION =
            "bloge.durableTestRecoverySequenceRequest.v1";

    /** Applies the default version and freezes the ordered signal program. */
    public DurableTestRecoverySequenceRequest {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        clientRequestId = normalized(clientRequestId);
        expectedCheckpointFingerprint = normalized(expectedCheckpointFingerprint);
        signals = signals == null ? List.of() : List.copyOf(signals);
    }

    /** Rejects caller-owned orchestration, lease, result, and future fields. */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException(
                "Unknown durable recovery-sequence field: " + field);
    }

    /**
     * Exact server-issued fence controlling the first sequence step.
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
                    "Unknown durable recovery-sequence fence field: " + field);
        }
    }

    /**
     * One ordered business signal in the bounded recovery program.
     *
     * @param nodeId exact suspended signal node expected at this position
     * @param data explicit JSON signal value; JSON null represents no value
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Signal(String nodeId, JsonNode data) {
        /** Normalizes the node and rejects an omitted signal value. */
        public Signal {
            nodeId = normalized(nodeId);
            if (data == null) {
                throw new IllegalArgumentException(
                        "Recovery-sequence signal data is required; use explicit JSON null");
            }
        }

        /** Rejects caller-owned timing, outcome, and future signal controls. */
        @JsonAnySetter
        public void rejectUnknownField(String field, Object value) {
            throw new IllegalArgumentException(
                    "Unknown durable recovery-sequence signal field: " + field);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
