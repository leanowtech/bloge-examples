package com.leanowtech.bloge.graphengine.model;

import com.leanowtech.bloge.core.runtime.identity.ExecutionIdentity;

import java.util.List;

/**
 * Stateless registration response for one remote worker inside the active tenant scope.
 *
 * @param workerId registering worker identifier
 * @param workerTopic logical worker topic used for durable polling
 * @param tenantId resolved tenant scope
 * @param namespace resolved namespace scope
 * @param assignments active deployment bindings that match the worker
 */
public record GraphRemoteWorkerRegistration(
        String workerId,
        String workerTopic,
        String tenantId,
        String namespace,
        List<GraphRemoteWorkerAssignment> assignments
) {
    public GraphRemoteWorkerRegistration {
        workerId = requireNonBlank(workerId, "workerId");
        workerTopic = requireNonBlank(workerTopic, "workerTopic");
        tenantId = resolveScopeValue(tenantId, ExecutionIdentity.DEFAULT_TENANT, "tenantId");
        namespace = resolveScopeValue(namespace, ExecutionIdentity.DEFAULT_NAMESPACE, "namespace");
        assignments = assignments == null ? List.of() : List.copyOf(assignments);
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
