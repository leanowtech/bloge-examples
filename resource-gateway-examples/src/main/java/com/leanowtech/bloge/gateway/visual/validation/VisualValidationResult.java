package com.leanowtech.bloge.gateway.visual.validation;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.List;

/**
 * Validation response for graph drafts.
 *
 * @param valid whether validation has no blocking errors
 * @param diagnostics validation diagnostics
 */
public record VisualValidationResult(
        boolean valid,
        List<VisualDiagnostic> diagnostics
) {
    /**
     * Creates a validation result.
     */
    public VisualValidationResult {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        valid = diagnostics.stream().noneMatch(VisualDiagnostic::error);
    }
}
