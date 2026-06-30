package com.leanowtech.bloge.gateway.visual.draft;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.List;

/**
 * Result returned after importing a portable visual graph draft bundle.
 *
 * @param schemaVersion import result schema version
 * @param imported whether a new draft was stored
 * @param draft stored draft when import succeeded
 * @param diagnostics import contract or target-environment compatibility diagnostics
 */
public record GraphDraftImportResult(
        String schemaVersion,
        boolean imported,
        GraphDraft draft,
        List<VisualDiagnostic> diagnostics
) {
    public static final String SCHEMA_VERSION = "bloge.visualGraphDraftImportResult.v1";

    /**
     * Creates an import result.
     */
    public GraphDraftImportResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    /**
     * @param draft stored draft
     * @param diagnostics target-environment diagnostics for the stored draft
     * @return successful import result
     */
    public static GraphDraftImportResult imported(GraphDraft draft, List<VisualDiagnostic> diagnostics) {
        return new GraphDraftImportResult("", true, draft, diagnostics);
    }

    /**
     * @param diagnostics blocking import diagnostics
     * @return rejected import result
     */
    public static GraphDraftImportResult rejected(List<VisualDiagnostic> diagnostics) {
        return new GraphDraftImportResult("", false, null, diagnostics);
    }
}
