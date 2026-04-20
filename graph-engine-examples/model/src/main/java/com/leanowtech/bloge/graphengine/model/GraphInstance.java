package com.leanowtech.bloge.graphengine.model;

import com.leanowtech.bloge.core.runtime.identity.ExecutionIdentity;
import com.leanowtech.bloge.core.spi.SystemTimeSource;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Product-layer instance projection that enriches durable runtime executions.
 *
 * @param instanceId execution identifier shared with the durable runtime
 * @param definitionKey business-facing definition key
 * @param versionId product-layer version identifier pinned at start time
 * @param tenantId tenant that owns the instance
 * @param namespace namespace that owns the instance
 * @param businessKey optional business correlation key
 * @param executionMode runtime family that executes the instance
 * @param status projected instance status
 * @param initiator caller that started the instance
 * @param variables start variables captured by the control plane
 * @param revision optimistic-lock revision
 * @param createdAt creation timestamp
 * @param updatedAt last update timestamp
 * @param completedAt completion timestamp for terminal instances
 */
public record GraphInstance(
        String instanceId,
        String definitionKey,
        String versionId,
        String tenantId,
        String namespace,
        String businessKey,
        GraphExecutionMode executionMode,
        GraphInstanceStatus status,
        String initiator,
        Map<String, Object> variables,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
) {
    public GraphInstance {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
        if (definitionKey == null || definitionKey.isBlank()) {
            throw new IllegalArgumentException("definitionKey must not be blank");
        }
        if (versionId == null || versionId.isBlank()) {
            throw new IllegalArgumentException("versionId must not be blank");
        }
        tenantId = resolveScopeValue(tenantId, ExecutionIdentity.DEFAULT_TENANT, "tenantId");
        namespace = resolveScopeValue(namespace, ExecutionIdentity.DEFAULT_NAMESPACE, "namespace");
        executionMode = Objects.requireNonNullElse(executionMode, GraphExecutionMode.GRAPH);
        status = Objects.requireNonNullElse(status, GraphInstanceStatus.RUNNING);
        variables = variables == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(variables));
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
