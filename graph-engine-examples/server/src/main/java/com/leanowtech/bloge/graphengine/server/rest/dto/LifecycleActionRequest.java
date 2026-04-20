package com.leanowtech.bloge.graphengine.server.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * HTTP payload for optimistic-lock protected instance lifecycle actions.
 *
 * @param expectedRevision expected instance revision
 * @param reason human-readable governance reason
 */
public record LifecycleActionRequest(
        @NotNull @PositiveOrZero Long expectedRevision,
        @NotBlank String reason
) {
}
