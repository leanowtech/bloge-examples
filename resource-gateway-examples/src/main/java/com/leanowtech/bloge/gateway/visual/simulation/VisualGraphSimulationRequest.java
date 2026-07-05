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
 * @param fixtures optional per-node output pins keyed by node id (author-supplied mock outputs)
 */
public record VisualGraphSimulationRequest(
        GraphDraft draft,
        Map<String, Object> context,
        String outputNode,
        Map<String, NodeFixture> fixtures
) {
    /**
     * Normalizes nullable fields.
     */
    public VisualGraphSimulationRequest {
        context = context == null ? Map.of() : new LinkedHashMap<>(context);
        outputNode = outputNode == null ? "" : outputNode;
        fixtures = fixtures == null ? Map.of() : new LinkedHashMap<>(fixtures);
    }

    /**
     * Backward-compatible constructor for callers that do not supply fixtures.
     *
     * @param draft the transient graph draft to simulate
     * @param context the initial graph context
     * @param outputNode optional output node override
     */
    public VisualGraphSimulationRequest(GraphDraft draft, Map<String, Object> context, String outputNode) {
        this(draft, context, outputNode, Map.of());
    }
}
