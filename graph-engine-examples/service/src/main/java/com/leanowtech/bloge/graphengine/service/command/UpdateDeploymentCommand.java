package com.leanowtech.bloge.graphengine.service.command;

import com.leanowtech.bloge.core.runtime.registry.VersionRoutingPolicy;
import com.leanowtech.bloge.graphengine.model.OperatorPlaneConfig;

import java.util.Objects;

/**
 * Command that updates mutable deployment routing metadata.
 */
public record UpdateDeploymentCommand(
        String deploymentId,
        long expectedRevision,
        VersionRoutingPolicy routingPolicy,
        OperatorPlaneConfig operatorPlaneConfig,
        boolean active
) {
    public UpdateDeploymentCommand {
        if (deploymentId == null || deploymentId.isBlank()) {
            throw new IllegalArgumentException("deploymentId must not be blank");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must be >= 0");
        }
        routingPolicy = Objects.requireNonNullElse(routingPolicy, new VersionRoutingPolicy.Latest());
        operatorPlaneConfig = Objects.requireNonNullElse(operatorPlaneConfig, OperatorPlaneConfig.defaults());
    }
}
