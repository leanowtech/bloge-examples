package com.leanowtech.bloge.graphengine.server.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * HTTP payload that completes one claimed remote-worker job.
 *
 * @param leaseToken active claim token
 * @param expectedRevision optimistic-lock revision from the poll/heartbeat response
 * @param output worker-produced output payload
 */
public record CompleteRemoteWorkerJobRequest(
        @NotBlank String leaseToken,
        @NotNull @PositiveOrZero Long expectedRevision,
        Object output
) {
}
