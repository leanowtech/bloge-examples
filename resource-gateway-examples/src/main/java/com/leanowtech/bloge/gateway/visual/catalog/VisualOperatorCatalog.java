package com.leanowtech.bloge.gateway.visual.catalog;

import java.util.List;
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
     * Finds one operator by reference.
     *
     * @param operatorRef operator reference
     * @return operator when present
     */
    Optional<OperatorDefinition> find(String operatorRef);
}
