package com.leanowtech.bloge.gateway.visual.authoring.application;

/**
 * Complete enterprise ownership coordinate for progressive library authoring.
 *
 * <p>The visual authoring core owns this value so persistence and lifecycle services do not
 * depend on gateway authentication types.</p>
 */
public record AuthoringScope(
        String tenantId,
        String organizationId,
        String projectId,
        String environmentId,
        String region
) {
    public AuthoringScope {
        tenantId = required(tenantId, "tenantId", 255);
        organizationId = required(organizationId, "organizationId", 255);
        projectId = required(projectId, "projectId", 255);
        environmentId = required(environmentId, "environmentId", 255);
        region = required(region, "region", 64);
    }

    private static String required(String value, String field, int maximumLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " must be present and bounded");
        }
        return normalized;
    }
}
