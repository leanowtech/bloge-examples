package com.leanowtech.bloge.gateway.visual.authoring.testing;

/**
 * Trusted identity projection accepted by the visual authoring test core.
 */
public record AuthoringTestPrincipal(
        String tenantId,
        String organizationId,
        String projectId,
        String environmentId,
        String region,
        String actorId
) {
    public AuthoringTestPrincipal {
        tenantId = normalized(tenantId);
        organizationId = normalized(organizationId);
        projectId = normalized(projectId);
        environmentId = normalized(environmentId);
        region = normalized(region);
        actorId = normalized(actorId);
    }

    AuthoringTestScope requireScope() {
        if (actorId.isBlank()) {
            throw new IllegalArgumentException("actorId must be present");
        }
        return new AuthoringTestScope(
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
