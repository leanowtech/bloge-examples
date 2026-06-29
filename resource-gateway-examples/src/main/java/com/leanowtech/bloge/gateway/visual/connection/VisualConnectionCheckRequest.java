package com.leanowtech.bloge.gateway.visual.connection;

import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

/**
 * Request to check whether a proposed canvas connection is schema-compatible.
 *
 * @param draft current graph draft
 * @param source proposed source endpoint
 * @param target proposed target endpoint
 * @param kind edge kind, defaults to data
 */
public record VisualConnectionCheckRequest(
        GraphDraft draft,
        GraphDraft.Endpoint source,
        GraphDraft.Endpoint target,
        String kind
) {
    /**
     * Creates a connection check request.
     */
    public VisualConnectionCheckRequest {
        source = source == null ? GraphDraft.Endpoint.empty() : source;
        target = target == null ? GraphDraft.Endpoint.empty() : target;
        kind = kind == null || kind.isBlank() ? "data" : kind;
    }
}
