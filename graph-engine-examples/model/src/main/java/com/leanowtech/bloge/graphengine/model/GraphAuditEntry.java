package com.leanowtech.bloge.graphengine.model;

import com.leanowtech.bloge.core.runtime.identity.ExecutionIdentity;
import com.leanowtech.bloge.core.spi.SystemTimeSource;
import com.leanowtech.bloge.runtime.audit.AuditEventType;

import java.time.Instant;
import java.util.Objects;

/**
 * Product-layer audit projection for one node lifecycle event within an instance.
 *
 * @param instanceId instance identifier
 * @param definitionKey owning definition key
 * @param versionId owning version identifier
 * @param tenantId tenant scope
 * @param namespace namespace scope
 * @param nodeId node identifier
 * @param operatorRef operator registry reference, when available
 * @param eventType audit event kind
 * @param inputJson serialized node input, when captured
 * @param outputJson serialized node output, when captured
 * @param errorMessage failure or retry message, when present
 * @param retryAttempt retry/iteration ordinal with execution-mode-dependent semantics:
 *                     for GRAPH and STATE_MACHINE projections this is the number of prior failed
 *                     attempts before the recorded event (0 = first attempt); for SESSION
 *                     projections this is the 1-based round ordinal within the phase recorded as
 *                     a {@code NODE_COMPLETE} audit entry. Callers should interpret the value
 *                     alongside the owning instance's execution mode.
 * @param elapsedMillis elapsed duration in milliseconds, when known
 * @param recordedAt timestamp when the event was recorded
 */
public record GraphAuditEntry(
        String instanceId,
        String definitionKey,
        String versionId,
        String tenantId,
        String namespace,
        String nodeId,
        String operatorRef,
        AuditEventType eventType,
        String inputJson,
        String outputJson,
        String errorMessage,
        int retryAttempt,
        Long elapsedMillis,
        Instant recordedAt
) {
    public GraphAuditEntry {
        instanceId = requireNonBlank(instanceId, "instanceId");
        definitionKey = requireNonBlank(definitionKey, "definitionKey");
        versionId = requireNonBlank(versionId, "versionId");
        tenantId = resolveScopeValue(tenantId, ExecutionIdentity.DEFAULT_TENANT, "tenantId");
        namespace = resolveScopeValue(namespace, ExecutionIdentity.DEFAULT_NAMESPACE, "namespace");
        nodeId = requireNonBlank(nodeId, "nodeId");
        eventType = Objects.requireNonNull(eventType, "eventType");
        if (retryAttempt < 0) {
            throw new IllegalArgumentException("retryAttempt must be >= 0");
        }
        if (elapsedMillis != null && elapsedMillis < 0) {
            throw new IllegalArgumentException("elapsedMillis must be >= 0");
        }
        recordedAt = recordedAt == null ? SystemTimeSource.INSTANCE.now() : recordedAt;
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
