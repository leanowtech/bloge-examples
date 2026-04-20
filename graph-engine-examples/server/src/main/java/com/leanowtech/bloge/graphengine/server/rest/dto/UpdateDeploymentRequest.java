package com.leanowtech.bloge.graphengine.server.rest.dto;

import com.leanowtech.bloge.core.runtime.registry.VersionRoutingPolicy;
import com.leanowtech.bloge.graphengine.model.OperatorPlaneConfig;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * HTTP payload that updates one deployment routing binding.
 *
 * @param expectedRevision optimistic-lock revision expected by the caller
 * @param routingPolicy version routing policy
 * @param operatorPlaneConfig operator-plane configuration
 * @param active whether the deployment should be active after the update
 */
public record UpdateDeploymentRequest(
        @NotNull @PositiveOrZero Long expectedRevision,
        VersionRoutingPolicy routingPolicy,
        OperatorPlaneConfig operatorPlaneConfig,
        boolean active
) {
}
