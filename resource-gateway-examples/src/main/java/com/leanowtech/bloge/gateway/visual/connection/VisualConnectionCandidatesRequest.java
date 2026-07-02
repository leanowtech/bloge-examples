package com.leanowtech.bloge.gateway.visual.connection;

import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import java.util.Locale;

/**
 * Request to discover schema-aware target candidates for one canvas connection source.
 *
 * @param draft current graph draft
 * @param source source endpoint being dragged from the canvas
 * @param kind connection kind, defaults to data
 * @param includeRejected whether blocked targets with diagnostics should be returned
 * @param limit maximum returned candidate rows
 */
public record VisualConnectionCandidatesRequest(
        GraphDraft draft,
        GraphDraft.Endpoint source,
        String kind,
        boolean includeRejected,
        int limit
) {
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    /**
     * Creates a candidate discovery request.
     */
    public VisualConnectionCandidatesRequest {
        source = source == null ? GraphDraft.Endpoint.empty() : source;
        kind = canonicalEdgeKind(kind);
        limit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    }

    /**
     * Backward-compatible constructor for callers that only need accepted data candidates.
     */
    public VisualConnectionCandidatesRequest(GraphDraft draft, GraphDraft.Endpoint source) {
        this(draft, source, "data", false, DEFAULT_LIMIT);
    }

    private static String canonicalEdgeKind(String value) {
        if (value == null || value.isBlank()) {
            return "data";
        }
        String trimmed = value.trim();
        return switch (trimmed.toLowerCase(Locale.ROOT)) {
            case "data" -> "data";
            case "dependency", "dependson", "depends_on" -> "dependency";
            case "route", "branch" -> "route";
            default -> trimmed;
        };
    }
}
