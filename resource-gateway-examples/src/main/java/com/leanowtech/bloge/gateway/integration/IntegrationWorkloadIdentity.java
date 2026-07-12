package com.leanowtech.bloge.gateway.integration;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/** Server-owned claims associated with a verified integration credential. */
public record IntegrationWorkloadIdentity(
        String identityId,
        String tenantId,
        String organizationId,
        String projectId,
        String environmentId,
        String region,
        String actorType,
        String actorId,
        String delegatedBy,
        Set<String> allowedPurposes,
        Instant expiresAt,
        boolean enabled
) {
    public IntegrationWorkloadIdentity {
        identityId = normalize(identityId);
        tenantId = normalize(tenantId);
        organizationId = normalize(organizationId);
        projectId = normalize(projectId);
        environmentId = normalize(environmentId);
        region = normalize(region);
        actorType = normalize(actorType).toUpperCase();
        actorId = normalize(actorId);
        delegatedBy = normalize(delegatedBy);
        Set<String> purposes = new LinkedHashSet<>();
        if (allowedPurposes != null) {
            allowedPurposes.stream().map(IntegrationWorkloadIdentity::normalize)
                    .map(String::toUpperCase).filter(value -> !value.isBlank()).forEach(purposes::add);
        }
        allowedPurposes = Set.copyOf(purposes);
        expiresAt = expiresAt == null ? Instant.MAX : expiresAt;
    }

    public boolean activeAt(Instant now) {
        Instant observedAt = now == null ? Instant.now() : now;
        return enabled && expiresAt.isAfter(observedAt);
    }

    public boolean allowsPurpose(String purpose) {
        return allowedPurposes.contains(normalize(purpose).toUpperCase());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
