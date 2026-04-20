package com.leanowtech.bloge.graphengine.server.rest.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * HTTP payload that completes one human task.
 *
 * @param output task output payload
 * @param userId completing user identity
 */
public record CompleteTaskRequest(
        Object output,
        @NotBlank String userId
) {
}
