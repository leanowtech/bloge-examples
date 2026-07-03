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
 * @param sourceKinds required operator source kinds
 * @param operatorLibraryIds required imported operator library owners
 * @param loweringModes required lowering modes
 * @param capabilities required catalog capability facets
 * @param runtimeReadinessStates required server-derived runtime readiness states
 */
public record OperatorCatalogQuery(
        String search,
        List<String> tags,
        boolean resourceOnly,
        boolean includeDeprecated,
        String tenantId,
        String namespace,
        String environment,
        List<String> sourceKinds,
        List<String> operatorLibraryIds,
        List<String> loweringModes,
        List<String> capabilities,
        List<String> runtimeReadinessStates
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
        sourceKinds = normalizeFacetValues(sourceKinds);
        operatorLibraryIds = normalizeExactValues(operatorLibraryIds);
        loweringModes = normalizeFacetValues(loweringModes);
        capabilities = normalizeFacetValues(capabilities);
        runtimeReadinessStates = normalizeFacetValues(runtimeReadinessStates);
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
     * Backward-compatible constructor for callers that do not filter by catalog facets.
     */
    public OperatorCatalogQuery(String search,
                                List<String> tags,
                                boolean resourceOnly,
                                boolean includeDeprecated,
                                String tenantId,
                                String namespace,
                                String environment) {
        this(search, tags, resourceOnly, includeDeprecated, tenantId, namespace, environment,
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /**
     * Backward-compatible constructor for callers that do not filter by runtime readiness.
     */
    public OperatorCatalogQuery(String search,
                                List<String> tags,
                                boolean resourceOnly,
                                boolean includeDeprecated,
                                String tenantId,
                                String namespace,
                                String environment,
                                List<String> sourceKinds,
                                List<String> loweringModes,
                                List<String> capabilities) {
        this(search, tags, resourceOnly, includeDeprecated, tenantId, namespace, environment,
                sourceKinds, List.of(), loweringModes, capabilities, List.of());
    }

    /**
     * Backward-compatible constructor for callers that do not filter by operator library owner.
     */
    public OperatorCatalogQuery(String search,
                                List<String> tags,
                                boolean resourceOnly,
                                boolean includeDeprecated,
                                String tenantId,
                                String namespace,
                                String environment,
                                List<String> sourceKinds,
                                List<String> loweringModes,
                                List<String> capabilities,
                                List<String> runtimeReadinessStates) {
        this(search, tags, resourceOnly, includeDeprecated, tenantId, namespace, environment,
                sourceKinds, List.of(), loweringModes, capabilities, runtimeReadinessStates);
    }

    /**
     * @return default query
     */
    public static OperatorCatalogQuery all() {
        return new OperatorCatalogQuery("", List.of(), false, false, "", "", "");
    }

    private static List<String> normalizeFacetValues(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(java.util.Locale.ROOT).replace('_', '-'))
                .distinct()
                .toList();
    }

    private static List<String> normalizeExactValues(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
