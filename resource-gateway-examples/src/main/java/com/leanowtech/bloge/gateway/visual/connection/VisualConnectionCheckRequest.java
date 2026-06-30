package com.leanowtech.bloge.gateway.visual.connection;

import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

/**
 * Request to check whether a proposed canvas connection is schema-compatible.
 *
 * @param draft current graph draft
 * @param source proposed source endpoint
 * @param target proposed target endpoint
 * @param kind edge kind, defaults to data
 * @param condition route condition for control-flow edges
 */
public record VisualConnectionCheckRequest(
        GraphDraft draft,
        GraphDraft.Endpoint source,
        GraphDraft.Endpoint target,
        String kind,
        String condition
) {
    /**
     * Creates a connection check request.
     */
    public VisualConnectionCheckRequest {
        source = source == null ? GraphDraft.Endpoint.empty() : source;
        target = target == null ? GraphDraft.Endpoint.empty() : target;
        kind = kind == null || kind.isBlank() ? "data" : kind;
        condition = condition == null ? "" : condition.trim();
    }

    /**
     * Backward-compatible constructor for callers that do not supply route conditions.
     */
    public VisualConnectionCheckRequest(GraphDraft draft,
                                        GraphDraft.Endpoint source,
                                        GraphDraft.Endpoint target,
                                        String kind) {
        this(draft, source, target, kind, "");
    }
}
