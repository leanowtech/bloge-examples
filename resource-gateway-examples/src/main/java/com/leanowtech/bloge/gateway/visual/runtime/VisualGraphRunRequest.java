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
 */
public record VisualGraphRunRequest(
        GraphDraft draft,
        Map<String, Object> context,
        String outputNode
) {
    /**
     * Creates a run request.
     */
    public VisualGraphRunRequest {
        context = context == null ? Map.of() : new LinkedHashMap<>(context);
        outputNode = outputNode == null ? "" : outputNode;
    }
}
