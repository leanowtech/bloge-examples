package com.leanowtech.bloge.gateway.visual.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Run request for a previously stored visual graph draft.
 *
 * @param context initial graph context
 * @param outputNode optional output node override
 */
public record VisualStoredDraftRunRequest(
        Map<String, Object> context,
        String outputNode
) {
    /**
     * Creates a stored draft run request.
     */
    public VisualStoredDraftRunRequest {
        context = context == null ? Map.of() : new LinkedHashMap<>(context);
        outputNode = outputNode == null ? "" : outputNode;
    }
}
