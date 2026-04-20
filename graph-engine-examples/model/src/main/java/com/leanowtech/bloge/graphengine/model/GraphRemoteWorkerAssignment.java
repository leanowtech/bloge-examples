package com.leanowtech.bloge.graphengine.model;

import java.util.Objects;

/**
 * Registration-time projection of one active deployment binding that targets a remote worker.
 *
 * @param deploymentId active deployment identifier
 * @param definitionKey owning graph definition key
 * @param environment deployment environment
 * @param operatorRef business operator reference bound to the worker
 * @param binding remote worker binding declared on the deployment
 */
public record GraphRemoteWorkerAssignment(
        String deploymentId,
        String definitionKey,
        String environment,
        String operatorRef,
        RemoteWorkerBinding binding
) {
    public GraphRemoteWorkerAssignment {
        deploymentId = requireNonBlank(deploymentId, "deploymentId");
        definitionKey = requireNonBlank(definitionKey, "definitionKey");
        environment = requireNonBlank(environment, "environment");
        operatorRef = requireNonBlank(operatorRef, "operatorRef");
        binding = Objects.requireNonNull(binding, "binding");
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
