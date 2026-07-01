package com.leanowtech.bloge.gateway.visual.resource;

import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import java.util.List;

/**
 * Result for discovering importable operations in an OpenAPI document.
 *
 * @param operations discovered operations
 * @param validation parse and discovery diagnostics
 */
public record OpenApiOperationDiscoveryResult(
        List<OpenApiOperationSummary> operations,
        VisualValidationResult validation
) {
    /**
     * Canonicalizes nullable fields for a stable wire contract.
     */
    public OpenApiOperationDiscoveryResult {
        operations = operations == null ? List.of() : List.copyOf(operations);
        validation = validation == null ? new VisualValidationResult(true, List.of()) : validation;
    }
}
