package com.leanowtech.bloge.graphengine.service.command;

import com.leanowtech.bloge.core.runtime.registry.VersionRoutingPolicy;
import com.leanowtech.bloge.graphengine.model.OperatorPlaneConfig;

import java.util.Objects;

/**
 * Command that creates a deployment routing configuration for one definition.
 */
public record CreateDeploymentCommand(
        String definitionKey,
        String tenantId,
        String namespace,
        String environment,
        VersionRoutingPolicy routingPolicy,
        OperatorPlaneConfig operatorPlaneConfig,
        boolean active
) {
    public CreateDeploymentCommand {
        if (definitionKey == null || definitionKey.isBlank()) {
            throw new IllegalArgumentException("definitionKey must not be blank");
        }
        if (tenantId != null && tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (namespace != null && namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
        if (environment == null || environment.isBlank()) {
            throw new IllegalArgumentException("environment must not be blank");
        }
        routingPolicy = Objects.requireNonNullElse(routingPolicy, new VersionRoutingPolicy.Latest());
        operatorPlaneConfig = Objects.requireNonNullElse(operatorPlaneConfig, OperatorPlaneConfig.defaults());
    }
}
