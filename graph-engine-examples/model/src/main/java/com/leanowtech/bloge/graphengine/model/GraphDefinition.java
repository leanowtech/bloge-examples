package com.leanowtech.bloge.graphengine.model;

import com.leanowtech.bloge.core.runtime.identity.ExecutionIdentity;
import com.leanowtech.bloge.core.spi.SystemTimeSource;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Governance-facing graph definition identity that remains stable across versions.
 *
 * @param definitionId stable internal identifier
 * @param definitionKey business-facing key unique within tenant and namespace
 * @param tenantId tenant that owns the definition
 * @param namespace namespace that owns the definition
 * @param displayName human-readable display name
 * @param description optional description
 * @param category business category
 * @param labels free-form labels
 * @param ownerTeam owning team
 * @param rbacPolicy role-based access declaration
 * @param status definition lifecycle status
 * @param revision optimistic-lock revision
 * @param createdAt creation timestamp
 * @param updatedAt last update timestamp
 */
public record GraphDefinition(
        String definitionId,
        String definitionKey,
        String tenantId,
        String namespace,
        String displayName,
        String description,
        GraphCategory category,
        Map<String, String> labels,
        String ownerTeam,
        RbacPolicy rbacPolicy,
        GraphDefinitionStatus status,
        long revision,
        Instant createdAt,
        Instant updatedAt
) {
    public GraphDefinition {
        if (definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        if (definitionKey == null || definitionKey.isBlank()) {
            throw new IllegalArgumentException("definitionKey must not be blank");
        }
        tenantId = resolveScopeValue(tenantId, ExecutionIdentity.DEFAULT_TENANT, "tenantId");
        namespace = resolveScopeValue(namespace, ExecutionIdentity.DEFAULT_NAMESPACE, "namespace");
        displayName = displayName == null || displayName.isBlank() ? definitionKey : displayName;
        category = Objects.requireNonNullElse(category, GraphCategory.PIPELINE);
        labels = labels == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(labels));
        rbacPolicy = Objects.requireNonNullElse(rbacPolicy, new RbacPolicy(null, null, null, null));
        status = Objects.requireNonNullElse(status, GraphDefinitionStatus.ACTIVE);
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be >= 0");
        }
        createdAt = createdAt == null ? SystemTimeSource.INSTANCE.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    private static String resolveScopeValue(String value, String fallback, String fieldName) {
        if (value == null) {
            return fallback;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
