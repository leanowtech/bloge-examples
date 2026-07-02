package com.leanowtech.bloge.gateway.visual.validation;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.List;

/**
 * Validation response for graph drafts.
 *
 * @param valid whether validation has no blocking errors
 * @param diagnostics validation diagnostics
 * @param readiness server-derived graph runtime/design readiness
 * @param actionReadiness server-derived compile/run/publication action gates
 */
public record VisualValidationResult(
        boolean valid,
        List<VisualDiagnostic> diagnostics,
        VisualGraphReadiness readiness,
        VisualGraphActionReadiness actionReadiness
) {
    /**
     * Creates a validation result.
     */
    public VisualValidationResult {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        valid = diagnostics.stream().noneMatch(VisualDiagnostic::error);
        readiness = readiness == null ? VisualGraphReadiness.notAssessed() : readiness;
        actionReadiness = actionReadiness == null
                ? VisualGraphActionReadiness.from(valid, diagnostics, readiness)
                : actionReadiness;
    }

    /**
     * Backward-compatible constructor for callers that return graph readiness but not action readiness.
     */
    public VisualValidationResult(boolean valid,
                                  List<VisualDiagnostic> diagnostics,
                                  VisualGraphReadiness readiness) {
        this(valid, diagnostics, readiness, null);
    }

    /**
     * Backward-compatible constructor for callers that only return diagnostics.
     */
    public VisualValidationResult(boolean valid, List<VisualDiagnostic> diagnostics) {
        this(valid, diagnostics, VisualGraphReadiness.notAssessed(), null);
    }
}
