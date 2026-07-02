package com.leanowtech.bloge.gateway.visual.validation;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.List;

/**
 * Validation response for graph drafts.
 *
 * @param valid whether validation has no blocking errors
 * @param diagnostics validation diagnostics
 * @param readiness server-derived graph runtime/design readiness
 */
public record VisualValidationResult(
        boolean valid,
        List<VisualDiagnostic> diagnostics,
        VisualGraphReadiness readiness
) {
    /**
     * Creates a validation result.
     */
    public VisualValidationResult {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        valid = diagnostics.stream().noneMatch(VisualDiagnostic::error);
        readiness = readiness == null ? VisualGraphReadiness.notAssessed() : readiness;
    }

    /**
     * Backward-compatible constructor for callers that only return diagnostics.
     */
    public VisualValidationResult(boolean valid, List<VisualDiagnostic> diagnostics) {
        this(valid, diagnostics, VisualGraphReadiness.notAssessed());
    }
}
