package com.leanowtech.bloge.graphengine.server.rest.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * HTTP payload that registers one remote worker against the active deployment bindings.
 *
 * @param workerId stable worker identifier
 * @param workerTopic logical worker topic used for polling
 */
public record RegisterRemoteWorkerRequest(
        @NotBlank String workerId,
        @NotBlank String workerTopic
) {
}
