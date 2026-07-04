package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import java.util.Locale;

/**
 * Request to discover addable catalog operators compatible with one canvas source endpoint.
 *
 * @param draft current graph draft
 * @param source source endpoint whose output should feed a new operator
 * @param filter catalog filter applied before schema-fit evaluation
 * @param targetSurface target surface to inspect, such as input or config
 * @param includeRejected whether operators with no compatible target should be returned
 * @param limit maximum returned operator rows
 * @param offset zero-based offset after accepted/rejected filtering
 */
public record OperatorFitCandidatesRequest(
        GraphDraft draft,
        GraphDraft.Endpoint source,
        OperatorCatalogQuery filter,
        String targetSurface,
        boolean includeRejected,
        int limit,
        int offset
) {
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    /**
     * Creates a candidate-fit request.
     */
    public OperatorFitCandidatesRequest {
        source = source == null ? GraphDraft.Endpoint.empty() : source;
        filter = filter == null ? OperatorCatalogQuery.all() : filter;
        targetSurface = canonicalSurface(targetSurface);
        limit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        offset = Math.max(0, offset);
    }

    private static String canonicalSurface(String value) {
        if (value == null || value.isBlank()) {
            return "input";
        }
        String trimmed = value.trim();
        return switch (trimmed.toLowerCase(Locale.ROOT)) {
            case "input", "inputs" -> "input";
            case "config", "configuration" -> "config";
            case "all", "any", "*" -> "";
            default -> trimmed;
        };
    }
}
