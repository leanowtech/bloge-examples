package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import java.util.List;

/**
 * Result for discovering importable operations/messages in an AsyncAPI document.
 *
 * @param operations discovered operation/message projection candidates
 * @param validation parse and discovery diagnostics
 */
public record AsyncApiOperationDiscoveryResult(
        List<AsyncApiOperationSummary> operations,
        VisualValidationResult validation
) {
    /**
     * Canonicalizes nullable fields for a stable wire contract.
     */
    public AsyncApiOperationDiscoveryResult {
        operations = operations == null ? List.of() : List.copyOf(operations);
        validation = validation == null ? new VisualValidationResult(true, List.of()) : validation;
    }
}
