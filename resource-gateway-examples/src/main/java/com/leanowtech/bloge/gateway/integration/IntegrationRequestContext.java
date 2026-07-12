package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import java.util.LinkedHashMap;
import java.util.Map;

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
        String correlationId
) {
    public IntegrationRequestContext {
        tenantId = normalize(tenantId);
        organizationId = normalize(organizationId);
        projectId = normalize(projectId);
        environmentId = normalize(environmentId);
        region = normalize(region);
        actorType = normalize(actorType).toUpperCase();
        actorId = normalize(actorId);
        delegatedBy = normalize(delegatedBy);
        purpose = normalize(purpose).toUpperCase();
        correlationId = normalize(correlationId);
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
        if (draft == null
                || !tenantId.equals(draft.tenantId())
                || !environmentId.equals(draft.environment())) {
            throw new IntegrationProblemException(IntegrationProblem.notFound(
                    "RG.INTEGRATION.DRAFT_NOT_FOUND",
                    "Draft was not found in the authorized integration scope.",
                    correlationId,
                    Map.of()
            ));
        }
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
