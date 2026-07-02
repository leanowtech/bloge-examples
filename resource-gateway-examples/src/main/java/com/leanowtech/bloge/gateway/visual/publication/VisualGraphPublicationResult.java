package com.leanowtech.bloge.gateway.visual.publication;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import java.util.List;

/**
 * Response returned by visual graph publication attempts.
 *
 * @param published whether publication succeeded
 * @param publication immutable artifact when publication succeeded
 * @param diagnostics validation/generation diagnostics when publication failed or requires review
 * @param validation graph validation result captured before publication or rejection
 */
public record VisualGraphPublicationResult(
        boolean published,
        VisualGraphPublication publication,
        List<VisualDiagnostic> diagnostics,
        VisualValidationResult validation
) {
    /**
     * Creates a publication result.
     */
    public VisualGraphPublicationResult {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        validation = validation == null && publication != null ? publication.validation() : validation;
        published = publication != null && diagnostics.stream().noneMatch(VisualDiagnostic::error);
    }

    /**
     * Backward-compatible constructor for callers that do not expose validation readiness.
     */
    public VisualGraphPublicationResult(boolean published,
                                        VisualGraphPublication publication,
                                        List<VisualDiagnostic> diagnostics) {
        this(published, publication, diagnostics, publication == null ? null : publication.validation());
    }

    /**
     * @param publication published artifact
     * @return successful result
     */
    public static VisualGraphPublicationResult published(VisualGraphPublication publication) {
        return new VisualGraphPublicationResult(true, publication, List.of(),
                publication == null ? null : publication.validation());
    }

    /**
     * @param diagnostics rejection diagnostics
     * @return rejected result
     */
    public static VisualGraphPublicationResult rejected(List<VisualDiagnostic> diagnostics) {
        return new VisualGraphPublicationResult(false, null, diagnostics, null);
    }

    /**
     * @param validation validation result that rejected or constrained publication
     * @return rejected result with validation readiness preserved
     */
    public static VisualGraphPublicationResult rejected(VisualValidationResult validation) {
        return rejected(validation == null ? List.of() : validation.diagnostics(), validation);
    }

    /**
     * @param diagnostics rejection diagnostics
     * @param validation validation result that rejected or constrained publication
     * @return rejected result with validation readiness preserved
     */
    public static VisualGraphPublicationResult rejected(List<VisualDiagnostic> diagnostics,
                                                       VisualValidationResult validation) {
        return new VisualGraphPublicationResult(false, null, diagnostics, validation);
    }
}
