package com.leanowtech.bloge.gateway.integration;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Locale;

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
        boolean enabled,
        Set<String> groups,
        String clearance,
        String delegationGrantId,
        Instant delegationExpiresAt
) {
    private static final Set<String> CLEARANCE_LEVELS = Set.of(
            "PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");

    public IntegrationWorkloadIdentity(String identityId,
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
                                       boolean enabled) {
        this(identityId, tenantId, organizationId, projectId, environmentId, region, actorType, actorId,
                delegatedBy, allowedPurposes, expiresAt, enabled, Set.of(), "PUBLIC", "", Instant.MAX);
    }

    public IntegrationWorkloadIdentity {
        identityId = normalize(identityId);
        tenantId = normalize(tenantId);
        organizationId = normalize(organizationId);
        projectId = normalize(projectId);
        environmentId = normalize(environmentId);
        region = normalize(region);
        actorType = normalize(actorType).toUpperCase(Locale.ROOT);
        actorId = normalize(actorId);
        delegatedBy = normalize(delegatedBy);
        Set<String> purposes = new LinkedHashSet<>();
        if (allowedPurposes != null) {
            allowedPurposes.stream().map(IntegrationWorkloadIdentity::normalize)
                    .map(value -> value.toUpperCase(Locale.ROOT)).filter(value -> !value.isBlank())
                    .forEach(purposes::add);
        }
        allowedPurposes = Set.copyOf(purposes);
        expiresAt = expiresAt == null ? Instant.MAX : expiresAt;
        Set<String> normalizedGroups = new LinkedHashSet<>();
        if (groups != null) {
            groups.stream().map(IntegrationWorkloadIdentity::normalize).filter(value -> !value.isBlank())
                    .forEach(normalizedGroups::add);
        }
        if (normalizedGroups.size() > 64
                || normalizedGroups.stream().anyMatch(value -> value.length() > 128
                || value.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("Integration identity groups must be bounded identifiers");
        }
        groups = Set.copyOf(normalizedGroups);
        clearance = normalize(clearance).toUpperCase(Locale.ROOT);
        if (clearance.isBlank()) {
            clearance = "PUBLIC";
        }
        if (!CLEARANCE_LEVELS.contains(clearance)) {
            throw new IllegalArgumentException("Unsupported integration identity clearance: " + clearance);
        }
        delegationGrantId = normalize(delegationGrantId);
        delegationExpiresAt = delegationExpiresAt == null ? Instant.MAX : delegationExpiresAt;
    }

    public boolean activeAt(Instant now) {
        Instant observedAt = now == null ? Instant.now() : now;
        boolean delegationActive = delegatedBy.isBlank()
                || !delegationGrantId.isBlank() && delegationExpiresAt.isAfter(observedAt);
        return enabled && expiresAt.isAfter(observedAt) && delegationActive;
    }

    public boolean allowsPurpose(String purpose) {
        return allowedPurposes.contains(normalize(purpose).toUpperCase(Locale.ROOT));
    }

    public boolean hasClearanceAtLeast(String requiredClearance) {
        String required = normalize(requiredClearance).toUpperCase(Locale.ROOT);
        return CLEARANCE_LEVELS.contains(required) && clearanceRank(clearance) >= clearanceRank(required);
    }

    private static int clearanceRank(String value) {
        return switch (normalize(value).toUpperCase(Locale.ROOT)) {
            case "PUBLIC" -> 0;
            case "INTERNAL" -> 1;
            case "CONFIDENTIAL" -> 2;
            case "RESTRICTED" -> 3;
            default -> -1;
        };
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
