package com.leanowtech.bloge.graphengine.model;

import com.leanowtech.bloge.core.runtime.identity.ExecutionIdentity;
import com.leanowtech.bloge.core.runtime.registry.VersionRoutingPolicy;
import com.leanowtech.bloge.core.spi.SystemTimeSource;

import java.time.Instant;
import java.util.Objects;

/**
 * Deployment-scoped routing configuration for one graph definition.
 *
 * @param deploymentId internal deployment identifier
 * @param definitionKey business-facing definition key
 * @param tenantId tenant that owns the deployment
 * @param namespace namespace that owns the deployment
 * @param environment environment label such as production or staging
 * @param routingPolicy version routing policy applied to new starts
 * @param operatorPlaneConfig operator execution-plane configuration
 * @param active whether the deployment is currently active
 * @param revision optimistic-lock revision
 * @param createdAt creation timestamp
 * @param updatedAt last update timestamp
 */
public record GraphDeployment(
        String deploymentId,
        String definitionKey,
        String tenantId,
        String namespace,
        String environment,
        VersionRoutingPolicy routingPolicy,
        OperatorPlaneConfig operatorPlaneConfig,
        boolean active,
        long revision,
        Instant createdAt,
        Instant updatedAt
) {
    public GraphDeployment {
        if (deploymentId == null || deploymentId.isBlank()) {
            throw new IllegalArgumentException("deploymentId must not be blank");
        }
        if (definitionKey == null || definitionKey.isBlank()) {
            throw new IllegalArgumentException("definitionKey must not be blank");
        }
        if (environment == null || environment.isBlank()) {
            throw new IllegalArgumentException("environment must not be blank");
        }
        tenantId = resolveScopeValue(tenantId, ExecutionIdentity.DEFAULT_TENANT, "tenantId");
        namespace = resolveScopeValue(namespace, ExecutionIdentity.DEFAULT_NAMESPACE, "namespace");
        routingPolicy = Objects.requireNonNullElse(routingPolicy, new VersionRoutingPolicy.Latest());
        operatorPlaneConfig = Objects.requireNonNullElse(operatorPlaneConfig, OperatorPlaneConfig.defaults());
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
