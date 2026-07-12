package com.leanowtech.bloge.gateway.visual.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Run request for a previously stored visual graph draft.
 *
 * @param context initial graph context
 * @param outputNode optional output node override
 * @param expectedRevision optional draft revision precondition; zero keeps legacy latest-run semantics
 * @param runIntent optional deadline and fenced cancellation intent
 */
public record VisualStoredDraftRunRequest(
        Map<String, Object> context,
        String outputNode,
        long expectedRevision,
        VisualRunIntent runIntent
) {
    /**
     * Creates a stored draft run request.
     */
    public VisualStoredDraftRunRequest {
        context = context == null ? Map.of() : new LinkedHashMap<>(context);
        outputNode = outputNode == null ? "" : outputNode;
        expectedRevision = Math.max(0, expectedRevision);
        runIntent = runIntent == null ? VisualRunIntent.unmanaged() : runIntent;
    }

    /**
     * Backward-compatible constructor for callers that only pass context and output selection.
     */
    public VisualStoredDraftRunRequest(Map<String, Object> context, String outputNode) {
        this(context, outputNode, 0, VisualRunIntent.unmanaged());
    }

    /** Backward-compatible unmanaged request with a revision precondition. */
    public VisualStoredDraftRunRequest(Map<String, Object> context, String outputNode, long expectedRevision) {
        this(context, outputNode, expectedRevision, VisualRunIntent.unmanaged());
    }
}
