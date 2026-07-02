package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.List;

/**
 * Validation response for user-provided operator-library preflight.
 *
 * @param valid whether validation has no blocking errors
 * @param diagnostics detailed diagnostics
 * @param impact machine-readable impact review
 * @param profile server-derived library review profile
 */
public record OperatorLibraryValidationResult(
        boolean valid,
        List<VisualDiagnostic> diagnostics,
        OperatorLibraryImpactReview impact,
        OperatorLibraryProfile profile
) {
    /**
     * Creates a validation result.
     */
    public OperatorLibraryValidationResult {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        valid = diagnostics.stream().noneMatch(VisualDiagnostic::error);
        impact = impact == null ? OperatorLibraryImpactReview.fromDiagnostics(diagnostics, List.of()) : impact;
        profile = profile == null ? OperatorLibraryProfile.empty() : profile;
    }

    /**
     * Backward-compatible constructor for callers that do not provide a profile.
     */
    public OperatorLibraryValidationResult(boolean valid,
                                           List<VisualDiagnostic> diagnostics,
                                           OperatorLibraryImpactReview impact) {
        this(valid, diagnostics, impact, OperatorLibraryProfile.empty());
    }
}
