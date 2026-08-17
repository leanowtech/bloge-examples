package com.leanowtech.bloge.gateway.visual.reference;

import java.util.Objects;

/**
 * Tenant and deployment coordinates used both as a candidate projection and as a search filter.
 * Empty filter fields are wildcards; candidate scopes must be fully specified.
 */
public record ReferenceScope(
        String tenantId,
        String organizationId,
        String projectId,
        String environmentId,
        String region
) {

    public ReferenceScope {
        tenantId = normalize(tenantId);
        organizationId = normalize(organizationId);
        projectId = normalize(projectId);
        environmentId = normalize(environmentId);
        region = normalize(region);
    }

    public boolean matches(ReferenceScope candidateScope) {
        Objects.requireNonNull(candidateScope, "candidateScope");
        return matches(tenantId, candidateScope.tenantId)
                && matches(organizationId, candidateScope.organizationId)
                && matches(projectId, candidateScope.projectId)
                && matches(environmentId, candidateScope.environmentId)
                && matches(region, candidateScope.region);
    }

    public boolean isFullySpecified() {
        return !tenantId.isEmpty()
                && !organizationId.isEmpty()
                && !projectId.isEmpty()
                && !environmentId.isEmpty()
                && !region.isEmpty();
    }

    private static boolean matches(String filter, String value) {
        return filter.isEmpty() || filter.equals(value);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
