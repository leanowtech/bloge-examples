package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/**
 * Trusted request identity and purpose propagated into integration services.
 */
public record IntegrationRequestContext(
        String tenantId,
        String organizationId,
        String projectId,
        String environmentId,
        String region,
        String actorType,
        String actorId,
        String delegatedBy,
        String purpose,
        String correlationId,
        Set<String> groups,
        String clearance,
        String delegationGrantId
) {
    public IntegrationRequestContext(String tenantId,
                                     String organizationId,
                                     String projectId,
                                     String environmentId,
                                     String region,
                                     String actorType,
                                     String actorId,
                                     String delegatedBy,
                                     String purpose,
                                     String correlationId) {
        this(tenantId, organizationId, projectId, environmentId, region, actorType, actorId, delegatedBy,
                purpose, correlationId, Set.of(), "PUBLIC", "");
    }

    public IntegrationRequestContext {
        tenantId = normalize(tenantId);
        organizationId = normalize(organizationId);
        projectId = normalize(projectId);
        environmentId = normalize(environmentId);
        region = normalize(region);
        actorType = normalize(actorType).toUpperCase(Locale.ROOT);
        actorId = normalize(actorId);
        delegatedBy = normalize(delegatedBy);
        purpose = normalize(purpose).toUpperCase(Locale.ROOT);
        correlationId = normalize(correlationId);
        Set<String> normalizedGroups = new LinkedHashSet<>();
        if (groups != null) {
            groups.stream().map(IntegrationRequestContext::normalize).filter(value -> !value.isBlank())
                    .forEach(normalizedGroups::add);
        }
        groups = Set.copyOf(normalizedGroups);
        clearance = normalize(clearance).toUpperCase(Locale.ROOT);
        if (clearance.isBlank()) {
            clearance = "PUBLIC";
        }
        delegationGrantId = normalize(delegationGrantId);
    }

    public void requireComplete() {
        Map<String, Object> missing = new LinkedHashMap<>();
        require(missing, "tenantId", tenantId);
        require(missing, "organizationId", organizationId);
        require(missing, "environmentId", environmentId);
        require(missing, "actorId", actorId);
        require(missing, "purpose", purpose);
        if (!missing.isEmpty()) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.INTEGRATION.CONTEXT_REQUIRED",
                    "Required integration identity context is missing.",
                    correlationId,
                    missing
            ));
        }
    }

    public void requireDraftScope(GraphDraft draft) {
        requireComplete();
        if (!matchesDraftScope(draft)) {
            throw new IntegrationProblemException(IntegrationProblem.notFound(
                    "RG.INTEGRATION.DRAFT_NOT_FOUND",
                    "Draft was not found in the authorized integration scope.",
                    correlationId,
                    Map.of()
            ));
        }
    }

    /**
     * Returns whether a visual draft belongs to this exact tenant, project namespace and
     * environment.
     *
     * <p>A draft id is only unique inside this triple. Callers that omit the project namespace can
     * otherwise expose or mutate a same-named draft owned by another project.</p>
     */
    public boolean matchesDraftScope(GraphDraft draft) {
        return draft != null
                && tenantId.equals(draft.tenantId())
                && projectId.equals(draft.namespace())
                && environmentId.equals(draft.environment());
    }

    public boolean hasClearanceAtLeast(String requiredClearance) {
        return clearanceRank(clearance) >= clearanceRank(requiredClearance);
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

    private static void require(Map<String, Object> missing, String field, String value) {
        if (value.isBlank()) {
            missing.put(field, "required");
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
