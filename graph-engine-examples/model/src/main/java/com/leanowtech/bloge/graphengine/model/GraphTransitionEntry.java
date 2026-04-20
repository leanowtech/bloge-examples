package com.leanowtech.bloge.graphengine.model;

import com.leanowtech.bloge.core.runtime.identity.ExecutionIdentity;
import com.leanowtech.bloge.core.spi.SystemTimeSource;

import java.time.Instant;
import java.util.Objects;

/**
 * Product-layer view of one instance status transition.
 *
 * @param transitionId transition log identifier
 * @param instanceId instance identifier
 * @param definitionKey owning definition key
 * @param versionId owning version identifier
 * @param tenantId tenant scope
 * @param namespace namespace scope
 * @param fromStatus previous product-layer status
 * @param toStatus next product-layer status
 * @param fromExecutionRevision previous durable execution revision
 * @param toExecutionRevision next durable execution revision
 * @param transitionSource runtime component that emitted the transition
 * @param transitionReason optional human-readable reason
 * @param createdAt transition timestamp
 */
public record GraphTransitionEntry(
        String transitionId,
        String instanceId,
        String definitionKey,
        String versionId,
        String tenantId,
        String namespace,
        GraphInstanceStatus fromStatus,
        GraphInstanceStatus toStatus,
        long fromExecutionRevision,
        long toExecutionRevision,
        String transitionSource,
        String transitionReason,
        Instant createdAt
) {
    public GraphTransitionEntry {
        transitionId = requireNonBlank(transitionId, "transitionId");
        instanceId = requireNonBlank(instanceId, "instanceId");
        definitionKey = requireNonBlank(definitionKey, "definitionKey");
        versionId = requireNonBlank(versionId, "versionId");
        tenantId = resolveScopeValue(tenantId, ExecutionIdentity.DEFAULT_TENANT, "tenantId");
        namespace = resolveScopeValue(namespace, ExecutionIdentity.DEFAULT_NAMESPACE, "namespace");
        fromStatus = Objects.requireNonNull(fromStatus, "fromStatus");
        toStatus = Objects.requireNonNull(toStatus, "toStatus");
        transitionSource = requireNonBlank(transitionSource, "transitionSource");
        if (fromExecutionRevision < 0 || toExecutionRevision < 0) {
            throw new IllegalArgumentException("execution revisions must be >= 0");
        }
        createdAt = createdAt == null ? SystemTimeSource.INSTANCE.now() : createdAt;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
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
