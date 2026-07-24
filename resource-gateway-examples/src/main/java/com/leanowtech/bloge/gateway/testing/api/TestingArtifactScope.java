package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import java.util.Objects;

/**
 * Complete enterprise ownership coordinate for governed test assets.
 *
 * <p>Tenant and environment alone are not an authorization boundary in a large enterprise:
 * organizations, projects, and regions can legitimately reuse the same local artifact ids. This
 * value is therefore carried through repository APIs as one indivisible key.</p>
 *
 * @param tenantId owning tenant
 * @param organizationId owning organization
 * @param projectId owning project or namespace
 * @param environmentId isolated non-production environment
 * @param region deployment and data-residency region
 */
public record TestingArtifactScope(
        String tenantId,
        String organizationId,
        String projectId,
        String environmentId,
        String region
) {
    /** Validates and normalizes every ownership dimension. */
    public TestingArtifactScope {
        tenantId = required(tenantId, "tenantId", 255);
        organizationId = required(organizationId, "organizationId", 255);
        projectId = required(projectId, "projectId", 255);
        environmentId = required(environmentId, "environmentId", 255);
        region = required(region, "region", 64);
    }

    /**
     * Creates a scope from authenticated request identity.
     *
     * @param identity trusted request context
     * @return complete artifact scope
     */
    public static TestingArtifactScope from(IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity");
        return new TestingArtifactScope(
                identity.tenantId(),
                identity.organizationId(),
                identity.projectId(),
                identity.environmentId(),
                identity.region());
    }

    private static String required(String value, String field, int maximumLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " must be present and bounded");
        }
        return normalized;
    }
}
