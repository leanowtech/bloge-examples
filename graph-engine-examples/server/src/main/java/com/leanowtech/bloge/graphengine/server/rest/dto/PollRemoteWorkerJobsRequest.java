package com.leanowtech.bloge.graphengine.server.rest.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.time.Duration;

/**
 * HTTP payload that polls and claims remote-worker jobs for one topic.
 *
 * @param workerId claiming worker identifier
 * @param limit optional maximum number of jobs to claim
 * @param leaseDuration optional lease duration granted to claimed jobs
 */
public record PollRemoteWorkerJobsRequest(
        @NotBlank String workerId,
        @Min(1) Integer limit,
        Duration leaseDuration
) {
}
