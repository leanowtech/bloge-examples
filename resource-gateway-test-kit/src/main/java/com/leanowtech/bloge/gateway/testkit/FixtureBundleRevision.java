package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Immutable stored-fixture revision returned by the Resource Gateway fixture registry.
 *
 * @param fixtureBundleId stable fixture id
 * @param revision immutable registry revision
 * @param fingerprint canonical fixture fingerprint
 * @param tenantId verified tenant scope
 * @param organizationId verified organization scope
 * @param projectId verified project scope
 * @param environmentId verified environment scope
 * @param region verified region scope
 * @param createdAt registry commit time
 * @param createdBy verified actor id
 * @param rawResponse defensive copy of the complete decoded response
 */
public record FixtureBundleRevision(
        String fixtureBundleId,
        long revision,
        String fingerprint,
        String tenantId,
        String organizationId,
        String projectId,
        String environmentId,
        String region,
        Instant createdAt,
        String createdBy,
        JsonNode rawResponse
) {
    /** Protects the raw response at construction time. */
    public FixtureBundleRevision {
        rawResponse = rawResponse == null ? null : rawResponse.deepCopy();
    }

    /**
     * Source-compatible constructor for historical v1 client projections.
     *
     * @param fixtureBundleId stable fixture identifier
     * @param revision immutable registry revision
     * @param fingerprint canonical fixture fingerprint
     * @param tenantId verified tenant scope
     * @param environmentId verified environment scope
     * @param createdAt registry commit time
     * @param createdBy verified actor id
     * @param rawResponse defensive copy of the complete decoded response
     */
    public FixtureBundleRevision(
            String fixtureBundleId,
            long revision,
            String fingerprint,
            String tenantId,
            String environmentId,
            Instant createdAt,
            String createdBy,
            JsonNode rawResponse) {
        this(fixtureBundleId, revision, fingerprint, tenantId, "", "",
                environmentId, "", createdAt, createdBy, rawResponse);
    }

    /**
     * Returns the complete decoded registry response without exposing mutable internal state.
     *
     * @return defensive copy of the decoded response
     */
    @Override
    public JsonNode rawResponse() {
        return rawResponse == null ? null : rawResponse.deepCopy();
    }

    /** Creates a defensive immutable projection from a validated protocol response. */
    static FixtureBundleRevision from(JsonNode response) {
        String created = response.path("createdAt").asText();
        return new FixtureBundleRevision(response.path("fixtureBundleId").asText(),
                response.path("revision").asLong(), response.path("fingerprint").asText(),
                response.path("tenantId").asText(), response.path("organizationId").asText(),
                response.path("projectId").asText(), response.path("environmentId").asText(),
                response.path("region").asText(),
                created.isBlank() ? null : Instant.parse(created), response.path("createdBy").asText(),
                response.deepCopy());
    }
}
