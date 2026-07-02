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
 * @param offset zero-based offset after accepted/rejected filtering
 * @param targetNodeId optional target node filter for large-canvas focused discovery
 * @param targetSurface optional target surface filter, such as input, config, dependency, route, or control
 */
public record VisualConnectionCandidatesRequest(
        GraphDraft draft,
        GraphDraft.Endpoint source,
        String kind,
        boolean includeRejected,
        int limit,
        int offset,
        String targetNodeId,
        String targetSurface
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
        offset = Math.max(0, offset);
        targetNodeId = targetNodeId == null ? "" : targetNodeId.trim();
        targetSurface = canonicalSurface(targetSurface);
    }

    /**
     * Backward-compatible constructor for callers that only need count-limited candidate discovery.
     */
    public VisualConnectionCandidatesRequest(GraphDraft draft,
                                             GraphDraft.Endpoint source,
                                             String kind,
                                             boolean includeRejected,
                                             int limit) {
        this(draft, source, kind, includeRejected, limit, 0, "", "");
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

    private static String canonicalSurface(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        return switch (trimmed.toLowerCase(Locale.ROOT)) {
            case "input", "inputs" -> "input";
            case "config", "configuration" -> "config";
            case "dependency", "depends_on", "dependson" -> "dependency";
            case "route", "branch" -> "route";
            case "control" -> "control";
            default -> trimmed;
        };
    }
}
