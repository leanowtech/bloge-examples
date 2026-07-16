package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.LinkedHashMap;
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
     * Returns BLOGE expression functions visible to expression editors for the current authoring scope.
     *
     * @param query query options used to shape the catalog window
     * @return function catalog for auto-completion and signature help
     */
    default List<OperatorLibrary.BuiltInFunction> builtInFunctions(OperatorCatalogQuery query) {
        return BuiltInFunctionCatalog.defaults();
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

    /**
     * Resolves a set of operator references against one logical catalog view.
     *
     * <p>The default implementation preserves compatibility for lightweight catalogs. Catalogs backed by
     * projection, persistence, or remote calls should override this method so one bulk lookup does not rebuild
     * the complete catalog for every reference.</p>
     *
     * @param operatorRefs operator references to resolve
     * @return immutable map containing the references visible in this catalog view
     */
    default Map<String, OperatorDefinition> findAll(Iterable<String> operatorRefs) {
        if (operatorRefs == null) {
            return Map.of();
        }
        Map<String, OperatorDefinition> resolved = new LinkedHashMap<>();
        for (String operatorRef : operatorRefs) {
            if (operatorRef == null || operatorRef.isBlank() || resolved.containsKey(operatorRef)) {
                continue;
            }
            find(operatorRef).ifPresent(operator -> resolved.put(operatorRef, operator));
        }
        return Map.copyOf(resolved);
    }
}
