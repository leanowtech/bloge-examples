package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.domain.WorkerQuarantineRequestIndexMode;

/**
 * Challenge-bound request for one short-lived request-index rollout proof.
 *
 * @param schemaVersion exact request protocol version
 * @param challenge caller-generated base64url or UUID-shaped nonce
 * @param targetMode immediate rollout mode the deployment authority wants to enter
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record WorkerQuarantineRequestIndexReplicaProofRequest(
        String schemaVersion,
        String challenge,
        WorkerQuarantineRequestIndexMode targetMode) {

    public static final String SCHEMA_VERSION =
            "bloge.workerQuarantineRequestIndexReplicaProofRequest.v1";

    /** Normalizes text while retaining fail-closed schema validation in the service boundary. */
    public WorkerQuarantineRequestIndexReplicaProofRequest {
        schemaVersion = schemaVersion == null ? "" : schemaVersion.trim();
        challenge = challenge == null ? "" : challenge.trim();
    }

    /** Rejects caller-selected instance, artifact, inventory, signature, or future fields. */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unknown request-index rollout proof field: " + field);
    }
}
