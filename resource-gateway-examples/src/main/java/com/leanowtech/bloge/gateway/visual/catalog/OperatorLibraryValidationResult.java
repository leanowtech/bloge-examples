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
 * @param importReadiness server-derived import readiness summary
 */
public record OperatorLibraryValidationResult(
        boolean valid,
        List<VisualDiagnostic> diagnostics,
        OperatorLibraryImpactReview impact,
        OperatorLibraryProfile profile,
        OperatorLibraryImportReadiness importReadiness
) {
    /**
     * Creates a validation result.
     */
    public OperatorLibraryValidationResult {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        valid = diagnostics.stream().noneMatch(VisualDiagnostic::error);
        impact = impact == null ? OperatorLibraryImpactReview.fromDiagnostics(diagnostics, List.of()) : impact;
        profile = profile == null ? OperatorLibraryProfile.empty() : profile;
        importReadiness = importReadiness == null
                ? OperatorLibraryImportReadiness.from(valid, diagnostics, impact, profile)
                : importReadiness;
    }

    /**
     * Backward-compatible constructor for callers that do not provide import readiness.
     */
    public OperatorLibraryValidationResult(boolean valid,
                                           List<VisualDiagnostic> diagnostics,
                                           OperatorLibraryImpactReview impact,
                                           OperatorLibraryProfile profile) {
        this(valid, diagnostics, impact, profile, (OperatorLibraryImportReadiness) null);
    }

    /**
     * Constructor for callers that have the submitted library snapshot available for
     * per-operator import readiness evidence.
     */
    public OperatorLibraryValidationResult(boolean valid,
                                           List<VisualDiagnostic> diagnostics,
                                           OperatorLibraryImpactReview impact,
                                           OperatorLibraryProfile profile,
                                           OperatorLibrary library) {
        this(validFromDiagnostics(diagnostics), diagnostics, impact, profile,
                OperatorLibraryImportReadiness.from(validFromDiagnostics(diagnostics), diagnostics, impact, profile,
                        library));
    }

    /**
     * Backward-compatible constructor for callers that do not provide a profile.
     */
    public OperatorLibraryValidationResult(boolean valid,
                                           List<VisualDiagnostic> diagnostics,
                                           OperatorLibraryImpactReview impact) {
        this(valid, diagnostics, impact, OperatorLibraryProfile.empty());
    }

    private static boolean validFromDiagnostics(List<VisualDiagnostic> diagnostics) {
        return diagnostics == null || diagnostics.stream().noneMatch(VisualDiagnostic::error);
    }
}
