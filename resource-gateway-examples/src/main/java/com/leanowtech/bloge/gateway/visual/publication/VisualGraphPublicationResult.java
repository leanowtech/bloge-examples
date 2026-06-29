package com.leanowtech.bloge.gateway.visual.publication;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.List;

/**
 * Response returned by visual graph publication attempts.
 *
 * @param published whether publication succeeded
 * @param publication immutable artifact when publication succeeded
 * @param diagnostics validation/generation diagnostics when publication failed
 */
public record VisualGraphPublicationResult(
        boolean published,
        VisualGraphPublication publication,
        List<VisualDiagnostic> diagnostics
) {
    /**
     * Creates a publication result.
     */
    public VisualGraphPublicationResult {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        published = publication != null && diagnostics.stream().noneMatch(VisualDiagnostic::error);
    }

    /**
     * @param publication published artifact
     * @return successful result
     */
    public static VisualGraphPublicationResult published(VisualGraphPublication publication) {
        return new VisualGraphPublicationResult(true, publication, List.of());
    }

    /**
     * @param diagnostics blocking diagnostics
     * @return rejected result
     */
    public static VisualGraphPublicationResult rejected(List<VisualDiagnostic> diagnostics) {
        return new VisualGraphPublicationResult(false, null, diagnostics);
    }
}
