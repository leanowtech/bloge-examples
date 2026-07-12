package com.leanowtech.bloge.gateway.visual.runtime;

import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Transient visual graph run request.
 *
 * @param draft graph draft to validate, compile, and run
 * @param context initial graph context
 * @param outputNode optional output node override
 * @param runIntent optional deadline and fenced cancellation intent
 */
public record VisualGraphRunRequest(
        GraphDraft draft,
        Map<String, Object> context,
        String outputNode,
        VisualRunIntent runIntent
) {
    /**
     * Creates a run request.
     */
    public VisualGraphRunRequest {
        context = context == null ? Map.of() : new LinkedHashMap<>(context);
        outputNode = outputNode == null ? "" : outputNode;
        runIntent = runIntent == null ? VisualRunIntent.unmanaged() : runIntent;
    }

    /** Backward-compatible unmanaged request. */
    public VisualGraphRunRequest(GraphDraft draft, Map<String, Object> context, String outputNode) {
        this(draft, context, outputNode, VisualRunIntent.unmanaged());
    }
}
