package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.List;
import java.util.Map;

/**
 * Visual operator catalog response envelope.
 *
 * @param schemaVersion response schema version
 * @param operators matching operators
 * @param diagnostics catalog diagnostics
 * @param facets count summary for matching operators
 * @param runtimeBindingProjections server-derived implementation binding projections
 * @param runtimeBindingProjectionStateCounts projection state counts
 * @param executablePromotionProjections server-derived executable promotion projections
 * @param executablePromotionStateCounts executable promotion state counts
 * @param total total operators matching the catalog filter before paging
 * @param unfilteredTotal total operators visible in the authoring scope before search/facet filters
 * @param displayedCount operators returned in this response window
 * @param itemLimit requested response window size
 * @param offset zero-based response window offset
 * @param hasMore whether another catalog window exists after this response
 * @param filter normalized catalog filter echoed for clients
 */
public record OperatorCatalogResponse(
        String schemaVersion,
        List<OperatorDefinition> operators,
        List<VisualDiagnostic> diagnostics,
        OperatorCatalogFacets facets,
        List<OperatorRuntimeBindingProjection> runtimeBindingProjections,
        Map<String, Integer> runtimeBindingProjectionStateCounts,
        List<OperatorExecutablePromotionProjection> executablePromotionProjections,
        Map<String, Integer> executablePromotionStateCounts,
        int total,
        int unfilteredTotal,
        int displayedCount,
        int itemLimit,
        int offset,
        boolean hasMore,
        OperatorCatalogQuery filter
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
        this(schemaVersion, operators, diagnostics, OperatorCatalogFacets.from(operators), List.of());
    }

    /**
     * Backward-compatible response constructor for callers that only customize facets.
     *
     * @param schemaVersion response schema version
     * @param operators matching operators
     * @param diagnostics catalog diagnostics
     * @param facets count summary
     */
    public OperatorCatalogResponse(String schemaVersion,
                                   List<OperatorDefinition> operators,
                                   List<VisualDiagnostic> diagnostics,
                                   OperatorCatalogFacets facets) {
        this(schemaVersion, operators, diagnostics, facets, List.of());
    }

    /**
     * Creates a response with runtime binding projections.
     *
     * @param schemaVersion response schema version
     * @param operators matching operators
     * @param diagnostics catalog diagnostics
     * @param facets count summary
     * @param runtimeBindingProjections server-derived runtime binding projections
     */
    public OperatorCatalogResponse(String schemaVersion,
                                   List<OperatorDefinition> operators,
                                   List<VisualDiagnostic> diagnostics,
                                   OperatorCatalogFacets facets,
                                   List<OperatorRuntimeBindingProjection> runtimeBindingProjections) {
        this(schemaVersion, operators, diagnostics, facets, runtimeBindingProjections,
                OperatorRuntimeBindingProjection.stateCounts(runtimeBindingProjections),
                OperatorExecutablePromotionProjection.from(runtimeBindingProjections));
    }

    /**
     * Creates a response with explicit runtime binding counts and derived promotion projections.
     *
     * @param schemaVersion response schema version
     * @param operators matching operators
     * @param diagnostics catalog diagnostics
     * @param facets count summary
     * @param runtimeBindingProjections server-derived runtime binding projections
     * @param runtimeBindingProjectionStateCounts runtime binding projection state counts
     */
    public OperatorCatalogResponse(String schemaVersion,
                                   List<OperatorDefinition> operators,
                                   List<VisualDiagnostic> diagnostics,
                                   OperatorCatalogFacets facets,
                                   List<OperatorRuntimeBindingProjection> runtimeBindingProjections,
                                   Map<String, Integer> runtimeBindingProjectionStateCounts) {
        this(schemaVersion, operators, diagnostics, facets, runtimeBindingProjections,
                runtimeBindingProjectionStateCounts,
                OperatorExecutablePromotionProjection.from(runtimeBindingProjections));
    }

    /**
     * Creates a response with executable promotion projections.
     *
     * @param schemaVersion response schema version
     * @param operators matching operators
     * @param diagnostics catalog diagnostics
     * @param facets count summary
     * @param runtimeBindingProjections server-derived runtime binding projections
     * @param runtimeBindingProjectionStateCounts runtime binding projection state counts
     * @param executablePromotionProjections server-derived executable promotion projections
     */
    public OperatorCatalogResponse(String schemaVersion,
                                   List<OperatorDefinition> operators,
                                   List<VisualDiagnostic> diagnostics,
                                   OperatorCatalogFacets facets,
                                   List<OperatorRuntimeBindingProjection> runtimeBindingProjections,
                                   Map<String, Integer> runtimeBindingProjectionStateCounts,
                                   List<OperatorExecutablePromotionProjection> executablePromotionProjections) {
        this(schemaVersion, operators, diagnostics, facets, runtimeBindingProjections,
                runtimeBindingProjectionStateCounts, executablePromotionProjections,
                OperatorExecutablePromotionProjection.stateCounts(executablePromotionProjections));
    }

    /**
     * Creates a response with executable promotion projections and state counts.
     *
     * @param schemaVersion response schema version
     * @param operators matching operators
     * @param diagnostics catalog diagnostics
     * @param facets count summary
     * @param runtimeBindingProjections server-derived runtime binding projections
     * @param runtimeBindingProjectionStateCounts runtime binding projection state counts
     * @param executablePromotionProjections server-derived executable promotion projections
     * @param executablePromotionStateCounts executable promotion state counts
     */
    public OperatorCatalogResponse(String schemaVersion,
                                   List<OperatorDefinition> operators,
                                   List<VisualDiagnostic> diagnostics,
                                   OperatorCatalogFacets facets,
                                   List<OperatorRuntimeBindingProjection> runtimeBindingProjections,
                                   Map<String, Integer> runtimeBindingProjectionStateCounts,
                                   List<OperatorExecutablePromotionProjection> executablePromotionProjections,
                                   Map<String, Integer> executablePromotionStateCounts) {
        this(schemaVersion, operators, diagnostics, facets, runtimeBindingProjections,
                runtimeBindingProjectionStateCounts, executablePromotionProjections, executablePromotionStateCounts,
                operators == null ? 0 : operators.size(),
                operators == null ? 0 : operators.size(),
                operators == null ? 0 : operators.size(),
                operators == null ? 0 : operators.size(),
                0,
                false,
                OperatorCatalogQuery.all());
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
        runtimeBindingProjections = runtimeBindingProjections == null
                ? List.of()
                : List.copyOf(runtimeBindingProjections);
        runtimeBindingProjectionStateCounts = runtimeBindingProjectionStateCounts == null
                ? OperatorRuntimeBindingProjection.stateCounts(runtimeBindingProjections)
                : Map.copyOf(runtimeBindingProjectionStateCounts);
        executablePromotionProjections = executablePromotionProjections == null
                ? OperatorExecutablePromotionProjection.from(runtimeBindingProjections)
                : List.copyOf(executablePromotionProjections);
        executablePromotionStateCounts = executablePromotionStateCounts == null
                ? OperatorExecutablePromotionProjection.stateCounts(executablePromotionProjections)
                : Map.copyOf(executablePromotionStateCounts);
        int safeOperatorCount = operators.size();
        total = Math.max(total, safeOperatorCount);
        unfilteredTotal = Math.max(unfilteredTotal, total);
        displayedCount = Math.max(0, displayedCount);
        itemLimit = Math.max(0, itemLimit);
        offset = Math.max(0, offset);
        filter = filter == null ? OperatorCatalogQuery.all() : filter;
    }
}
