package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Caller intent for one non-blocking durable recovery-worker pull.
 *
 * <p>Queue scope, owner identity, lease duration, candidate window, and selected run are all
 * server-owned. A caller supplies only a stable idempotency key, so it cannot probe another scope
 * or choose a favorable checkpoint.</p>
 *
 * @param schemaVersion worker acquisition request protocol version
 * @param clientRequestId caller-stable idempotency key; a new poll requires a new key
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record DurableTestWorkerAcquisitionRequest(
        String schemaVersion,
        String clientRequestId) {

    /** Current worker pull request protocol version. */
    public static final String SCHEMA_VERSION =
            "bloge.durableTestWorkerAcquisitionRequest.v1";

    /** Applies the current version only when the caller omitted it. */
    public DurableTestWorkerAcquisitionRequest {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        clientRequestId = normalized(clientRequestId);
    }

    /** Rejects caller-owned queue filters, run selectors, leases, or future fields. */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException(
                "Unknown durable worker acquisition field: " + field);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
