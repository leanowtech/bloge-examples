package com.leanowtech.bloge.graphengine.server.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * HTTP payload that reports one claimed remote-worker job as failed.
 *
 * @param leaseToken active claim token
 * @param expectedRevision optimistic-lock revision from the poll/heartbeat response
 * @param error failure description captured from the worker
 */
public record FailRemoteWorkerJobRequest(
        @NotBlank String leaseToken,
        @NotNull @PositiveOrZero Long expectedRevision,
        @NotBlank String error
) {
}
