package com.leanowtech.bloge.gateway.visual.catalog;

import java.util.List;

/**
 * Query options for the visual operator catalog.
 *
 * @param search free-text search
 * @param tags required tags
 * @param resourceOnly whether only resource virtual operators should be returned
 * @param includeDeprecated whether deprecated operators/contracts should be returned
 * @param tenantId tenant scope for availability filtering
 * @param namespace namespace scope for availability filtering
 * @param environment environment scope for availability filtering
 */
public record OperatorCatalogQuery(
        String search,
        List<String> tags,
        boolean resourceOnly,
        boolean includeDeprecated,
        String tenantId,
        String namespace,
        String environment
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
        tenantId = tenantId == null ? "" : tenantId.trim();
        namespace = namespace == null ? "" : namespace.trim();
        environment = environment == null ? "" : environment.trim();
    }

    /**
     * Backward-compatible constructor for callers that do not filter by authoring scope.
     */
    public OperatorCatalogQuery(String search,
                                List<String> tags,
                                boolean resourceOnly,
                                boolean includeDeprecated) {
        this(search, tags, resourceOnly, includeDeprecated, "", "", "");
    }

    /**
     * @return default query
     */
    public static OperatorCatalogQuery all() {
        return new OperatorCatalogQuery("", List.of(), false, false, "", "", "");
    }
}
