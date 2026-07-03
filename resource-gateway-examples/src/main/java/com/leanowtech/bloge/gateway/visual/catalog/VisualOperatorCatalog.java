package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Queryable visual operator catalog.
 */
public interface VisualOperatorCatalog {

    /**
     * Finds operators matching a query.
     *
     * @param query query options
     * @return matching operators
     */
    List<OperatorDefinition> list(OperatorCatalogQuery query);

    /**
     * Finds non-blocking diagnostics for a catalog query.
     *
     * @param query query options
     * @return catalog diagnostics
     */
    default List<VisualDiagnostic> diagnostics(OperatorCatalogQuery query) {
        return List.of();
    }

    /**
     * Returns the imported operator-library owner for catalog-visible operator refs.
     *
     * <p>This is a control-plane ownership snapshot for routing and impact review.
     * Native, Java, resource-backed, and publication-backed operators normally do not
     * have an imported library owner.</p>
     *
     * @param includeDeprecated include deprecated libraries
     * @return map from operator reference to owner library id
     */
    default Map<String, String> operatorLibraryIdsByOperatorRef(boolean includeDeprecated) {
        return Map.of();
    }

    /**
     * Returns server-derived runtime binding projections for a catalog window.
     *
     * <p>The projection is intentionally separate from {@link OperatorDefinition#runtimeReadiness()}:
     * imported libraries cannot declare their own active implementation binding, and a bound
     * implementation does not automatically mean request-response execution is available.</p>
     *
     * @param query query options used to shape the catalog window
     * @param operators already-resolved operator window
     * @return runtime binding projections aligned with the operator window
     */
    default List<OperatorRuntimeBindingProjection> runtimeBindingProjections(OperatorCatalogQuery query,
                                                                             List<OperatorDefinition> operators) {
        return List.of();
    }

    /**
     * Returns server-derived executable promotion projections for a catalog window.
     *
     * <p>The projection is intentionally separate from {@link OperatorDefinition#runtimeReadiness()}:
     * executable promotion facts can explain what remains blocked without letting imported
     * libraries or control-plane assertions forge request-response runtime executability.</p>
     *
     * @param query query options used to shape the catalog window
     * @param runtimeBindingProjections already-derived runtime binding projections
     * @return executable promotion projections aligned with the runtime binding projections
     */
    default List<OperatorExecutablePromotionProjection> executablePromotionProjections(
            OperatorCatalogQuery query,
            List<OperatorRuntimeBindingProjection> runtimeBindingProjections) {
        return OperatorExecutablePromotionProjection.from(runtimeBindingProjections);
    }

    /**
     * Finds one operator by reference.
     *
     * @param operatorRef operator reference
     * @return operator when present
     */
    Optional<OperatorDefinition> find(String operatorRef);
}
