package com.leanowtech.bloge.gateway.visual.authoring.testing;

/**
 * Complete enterprise ownership coordinate for visual authoring test evidence.
 *
 * <p>The visual core owns this value so its repositories remain independent from gateway
 * authentication and the broader testing control plane.</p>
 */
public record AuthoringTestScope(
        String tenantId,
        String organizationId,
        String projectId,
        String environmentId,
        String region
) {
    public AuthoringTestScope {
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
