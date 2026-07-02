package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.List;

/**
 * Visual operator catalog response envelope.
 *
 * @param schemaVersion response schema version
 * @param operators matching operators
 * @param diagnostics catalog diagnostics
 * @param facets count summary for matching operators
 */
public record OperatorCatalogResponse(
        String schemaVersion,
        List<OperatorDefinition> operators,
        List<VisualDiagnostic> diagnostics,
        OperatorCatalogFacets facets
) {
    /**
     * Backward-compatible response constructor.
     *
     * @param schemaVersion response schema version
     * @param operators matching operators
     * @param diagnostics catalog diagnostics
     */
    public OperatorCatalogResponse(String schemaVersion,
                                   List<OperatorDefinition> operators,
                                   List<VisualDiagnostic> diagnostics) {
        this(schemaVersion, operators, diagnostics, OperatorCatalogFacets.from(operators));
    }

    /**
     * Creates a response envelope.
     */
    public OperatorCatalogResponse {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? "bloge.visualOperatorCatalog.v1"
                : schemaVersion;
        operators = operators == null ? List.of() : List.copyOf(operators);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        facets = facets == null ? OperatorCatalogFacets.from(operators) : facets;
    }
}
