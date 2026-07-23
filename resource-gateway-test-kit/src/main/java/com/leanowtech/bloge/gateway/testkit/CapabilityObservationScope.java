package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Locally expected complete enterprise scope for offline observation verification.
 *
 * @param tenantId owning tenant
 * @param organizationId owning organization
 * @param projectId exact project namespace
 * @param environmentId exact environment namespace
 * @param region exact residency region
 */
public record CapabilityObservationScope(
        String tenantId,
        String organizationId,
        String projectId,
        String environmentId,
        String region
) {
    /** Validates complete non-blank verification coordinates. */
    public CapabilityObservationScope {
        tenantId = required(tenantId, "tenantId");
        organizationId = required(organizationId, "organizationId");
        projectId = required(projectId, "projectId");
        environmentId = required(environmentId, "environmentId");
        region = required(region, "region");
    }

    /**
     * Decodes one strict compatibility-fixture scope.
     *
     * @param value exact scope JSON
     * @return typed scope
     */
    public static CapabilityObservationScope from(JsonNode value) {
        if (value == null || !value.isObject() || value.size() != 5) {
            throw new IllegalArgumentException(
                    "capability observation scope is malformed");
        }
        return new CapabilityObservationScope(
                value.path("tenantId").asText(),
                value.path("organizationId").asText(),
                value.path("projectId").asText(),
                value.path("environmentId").asText(),
                value.path("region").asText());
    }

    /**
     * Compares this local scope with an untrusted observation scope value.
     *
     * @param value observation scope JSON
     * @return true only when every coordinate is exact
     */
    public boolean matches(JsonNode value) {
        return value != null
                && value.isObject()
                && value.size() == 5
                && tenantId.equals(value.path("tenantId").asText())
                && organizationId.equals(value.path("organizationId").asText())
                && projectId.equals(value.path("projectId").asText())
                && environmentId.equals(value.path("environmentId").asText())
                && region.equals(value.path("region").asText());
    }

    private static String required(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (exact.isBlank() || exact.length() > 255) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }
}
