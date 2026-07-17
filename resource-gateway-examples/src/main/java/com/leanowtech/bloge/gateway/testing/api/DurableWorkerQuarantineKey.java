package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Payload-free exact-checkpoint worker quarantine identity.
 *
 * @param runId durable execution identity
 * @param checkpointFingerprint exact isolated checkpoint closure
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record DurableWorkerQuarantineKey(String runId, String checkpointFingerprint) {
    /** Normalizes protocol text while leaving semantic validation to the service boundary. */
    public DurableWorkerQuarantineKey {
        runId = normalized(runId);
        checkpointFingerprint = normalized(checkpointFingerprint);
    }

    /** Rejects caller-selected scope, owner, or future identity fields. */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unknown worker quarantine key field: " + field);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
