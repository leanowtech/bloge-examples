package com.leanowtech.bloge.gateway.visual.simulation;

import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Request body for a visual graph mock-run (simulate).
 *
 * @param draft the transient graph draft to simulate
 * @param context the initial graph context (may be partial)
 * @param outputNode optional output node override; defaults to the draft's selected output node
 */
public record VisualGraphSimulationRequest(
        GraphDraft draft,
        Map<String, Object> context,
        String outputNode
) {
    /**
     * Normalizes nullable fields.
     */
    public VisualGraphSimulationRequest {
        context = context == null ? Map.of() : new LinkedHashMap<>(context);
        outputNode = outputNode == null ? "" : outputNode;
    }
}
