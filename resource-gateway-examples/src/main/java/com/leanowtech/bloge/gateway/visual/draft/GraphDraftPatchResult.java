package com.leanowtech.bloge.gateway.visual.draft;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.List;

/**
 * Response for optimistic visual graph draft patching.
 *
 * @param patched true when the patch was stored
 * @param draft current or newly stored draft
 * @param diagnostics patch diagnostics
 */
public record GraphDraftPatchResult(
        boolean patched,
        GraphDraft draft,
        List<VisualDiagnostic> diagnostics
) {
    /**
     * Creates a patch result.
     */
    public GraphDraftPatchResult {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        patched = draft != null && diagnostics.stream().noneMatch(VisualDiagnostic::error);
    }

    public static GraphDraftPatchResult patched(GraphDraft draft) {
        return new GraphDraftPatchResult(true, draft, List.of());
    }

    public static GraphDraftPatchResult rejected(GraphDraft draft, List<VisualDiagnostic> diagnostics) {
        return new GraphDraftPatchResult(false, draft, diagnostics);
    }
}
