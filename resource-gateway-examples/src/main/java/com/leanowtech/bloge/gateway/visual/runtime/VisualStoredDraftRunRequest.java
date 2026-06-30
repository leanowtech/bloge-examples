package com.leanowtech.bloge.gateway.visual.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Run request for a previously stored visual graph draft.
 *
 * @param context initial graph context
 * @param outputNode optional output node override
 * @param expectedRevision optional draft revision precondition; zero keeps legacy latest-run semantics
 */
public record VisualStoredDraftRunRequest(
        Map<String, Object> context,
        String outputNode,
        long expectedRevision
) {
    /**
     * Creates a stored draft run request.
     */
    public VisualStoredDraftRunRequest {
        context = context == null ? Map.of() : new LinkedHashMap<>(context);
        outputNode = outputNode == null ? "" : outputNode;
        expectedRevision = Math.max(0, expectedRevision);
    }

    /**
     * Backward-compatible constructor for callers that only pass context and output selection.
     */
    public VisualStoredDraftRunRequest(Map<String, Object> context, String outputNode) {
        this(context, outputNode, 0);
    }
}
