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
     * Finds one operator by reference.
     *
     * @param operatorRef operator reference
     * @return operator when present
     */
    Optional<OperatorDefinition> find(String operatorRef);
}
