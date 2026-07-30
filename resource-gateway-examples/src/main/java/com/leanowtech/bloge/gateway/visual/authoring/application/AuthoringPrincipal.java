package com.leanowtech.bloge.gateway.visual.authoring.application;

/**
 * Trusted identity projection accepted by the progressive authoring core.
 */
public record AuthoringPrincipal(
        String tenantId,
        String organizationId,
        String projectId,
        String environmentId,
        String region,
        String actorId
) {
    public AuthoringPrincipal {
        tenantId = normalized(tenantId);
        organizationId = normalized(organizationId);
        projectId = normalized(projectId);
        environmentId = normalized(environmentId);
        region = normalized(region);
        actorId = normalized(actorId);
    }

    public AuthoringScope requireScope() {
        if (actorId.isBlank() || actorId.length() > 255) {
            throw new IllegalArgumentException("actorId must be present and bounded");
        }
        return new AuthoringScope(
                tenantId,
                organizationId,
                projectId,
                environmentId,
                region);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
