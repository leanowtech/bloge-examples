package com.leanowtech.bloge.graphengine.server.rest.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Duration;

/**
 * HTTP payload that renews one claimed remote-worker lease.
 *
 * @param leaseToken active claim token
 * @param leaseDuration optional lease extension duration
 */
public record HeartbeatRemoteWorkerJobRequest(
        @NotBlank String leaseToken,
        Duration leaseDuration
) {
}
