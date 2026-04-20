package com.leanowtech.bloge.graphengine.server.rest.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * HTTP payload that cancels one human task.
 *
 * @param reason human-readable cancellation reason
 */
public record CancelTaskRequest(
        @NotBlank String reason
) {
}
