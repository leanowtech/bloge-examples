package com.leanowtech.bloge.graphengine.server.rest.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * HTTP payload that claims one human task for a concrete user.
 *
 * @param userId claimant identity
 */
public record ClaimTaskRequest(
        @NotBlank String userId
) {
}
