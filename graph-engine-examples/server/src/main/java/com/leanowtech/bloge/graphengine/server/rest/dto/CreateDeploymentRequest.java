package com.leanowtech.bloge.graphengine.server.rest.dto;

import com.leanowtech.bloge.core.runtime.registry.VersionRoutingPolicy;
import com.leanowtech.bloge.graphengine.model.OperatorPlaneConfig;

import jakarta.validation.constraints.NotBlank;

/**
 * HTTP payload that creates one deployment routing binding.
 *
 * @param definitionKey definition key that the deployment targets
 * @param environment deployment environment label
 * @param routingPolicy version routing policy
 * @param operatorPlaneConfig operator-plane configuration
 * @param active whether the deployment should become active immediately
 */
public record CreateDeploymentRequest(
        @NotBlank String definitionKey,
        @NotBlank String environment,
        VersionRoutingPolicy routingPolicy,
        OperatorPlaneConfig operatorPlaneConfig,
        boolean active
) {
}
