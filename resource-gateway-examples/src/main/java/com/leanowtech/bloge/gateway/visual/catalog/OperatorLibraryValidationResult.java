package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.List;

/**
 * Validation response for user-provided operator-library preflight.
 *
 * @param valid whether validation has no blocking errors
 * @param diagnostics detailed diagnostics
 * @param impact machine-readable impact review
 */
public record OperatorLibraryValidationResult(
        boolean valid,
        List<VisualDiagnostic> diagnostics,
        OperatorLibraryImpactReview impact
) {
    /**
     * Creates a validation result.
     */
    public OperatorLibraryValidationResult {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        valid = diagnostics.stream().noneMatch(VisualDiagnostic::error);
        impact = impact == null ? OperatorLibraryImpactReview.fromDiagnostics(diagnostics, List.of()) : impact;
    }
}
