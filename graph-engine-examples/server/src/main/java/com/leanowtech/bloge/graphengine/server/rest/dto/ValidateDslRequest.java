package com.leanowtech.bloge.graphengine.server.rest.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * HTTP payload that validates one raw BLOGE DSL document through the AI validation pipeline.
 *
 * @param dslSource raw BLOGE DSL source
 */
public record ValidateDslRequest(
        @NotBlank(message = "dslSource must not be blank")
        String dslSource
) {
}
