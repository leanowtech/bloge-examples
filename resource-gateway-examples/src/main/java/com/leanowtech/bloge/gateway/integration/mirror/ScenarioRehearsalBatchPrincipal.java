package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import java.util.Set;

/**
 * Credential-free authenticated principal snapshot retained with one asynchronous batch.
 *
 * <p>Credentials, bearer tokens, and correlation headers are never persisted. The worker
 * reconstructs only the exact enterprise identity and the fixed {@code MIRROR_REHEARSAL}
 * execution purpose. A later policy-authority integration may revalidate this snapshot before
 * each item without changing the durable protocol.</p>
 */
public record ScenarioRehearsalBatchPrincipal(
        CapabilitySnapshot.Scope scope,
        String actorType,
        String actorId,
        String delegatedBy,
        Set<String> groups,
        String clearance,
        String delegationGrantId
) {
    /** Captures one already-authenticated execution identity without credentials. */
    public ScenarioRehearsalBatchPrincipal {
        scope = java.util.Objects.requireNonNull(scope, "scope");
        actorType = required(actorType, "actorType");
        actorId = required(actorId, "actorId");
        delegatedBy = normalized(delegatedBy);
        groups = groups == null
                ? Set.of()
                : groups.stream()
                .map(ScenarioRehearsalBatchPrincipal::normalized)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        clearance = required(clearance, "clearance")
                .toUpperCase(java.util.Locale.ROOT);
        delegationGrantId = normalized(delegationGrantId);
    }

    /** @return isolated worker context with no caller credential or mutable request data */
    public IntegrationRequestContext toExecutionContext(
            String correlationId) {
        return new IntegrationRequestContext(
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                actorType,
                actorId,
                delegatedBy,
                "MIRROR_REHEARSAL",
                required(correlationId, "correlationId"),
                groups,
                clearance,
                delegationGrantId);
    }

    private static String required(String value, String field) {
        String normalized = normalized(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be blank");
        }
        return normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
