package com.leanowtech.bloge.graphengine.server.rest.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * HTTP payload that carries an optimistic-lock revision for an action endpoint.
 *
 * @param expectedRevision expected entity revision
 */
public record ExpectedRevisionRequest(
        @NotNull @PositiveOrZero Long expectedRevision
) {
}
