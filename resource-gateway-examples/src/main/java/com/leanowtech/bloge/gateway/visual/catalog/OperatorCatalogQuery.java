package com.leanowtech.bloge.gateway.visual.catalog;

import java.util.List;

/**
 * Query options for the visual operator catalog.
 *
 * @param search free-text search
 * @param tags required tags
 * @param resourceOnly whether only resource virtual operators should be returned
 * @param includeDeprecated whether deprecated operators/contracts should be returned
 */
public record OperatorCatalogQuery(
        String search,
        List<String> tags,
        boolean resourceOnly,
        boolean includeDeprecated
) {
    /**
     * Creates a query object.
     */
    public OperatorCatalogQuery {
        search = search == null ? "" : search.trim();
        tags = tags == null ? List.of() : tags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(String::trim)
                .toList();
    }

    /**
     * @return default query
     */
    public static OperatorCatalogQuery all() {
        return new OperatorCatalogQuery("", List.of(), false, false);
    }
}
