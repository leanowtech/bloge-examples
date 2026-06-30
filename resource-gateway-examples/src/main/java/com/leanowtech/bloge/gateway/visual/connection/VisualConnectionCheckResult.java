package com.leanowtech.bloge.gateway.visual.connection;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import java.util.List;

/**
 * Result of checking a proposed visual graph connection.
 *
 * @param accepted true when the proposed edge can be applied
 * @param edge normalized proposed edge
 * @param bindingKey storage key the canvas should use for a data/input binding
 * @param diagnostics schema, endpoint, or graph diagnostics for the proposed edge
 */
public record VisualConnectionCheckResult(
        boolean accepted,
        GraphDraft.DraftEdge edge,
        String bindingKey,
        List<VisualDiagnostic> diagnostics
) {
    /**
     * Creates a connection check result.
     */
    public VisualConnectionCheckResult {
        bindingKey = bindingKey == null ? "" : bindingKey;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        accepted = edge != null && diagnostics.stream().noneMatch(VisualDiagnostic::error);
    }

    /**
     * Backward-compatible constructor for checks that do not create input bindings.
     */
    public VisualConnectionCheckResult(boolean accepted,
                                       GraphDraft.DraftEdge edge,
                                       List<VisualDiagnostic> diagnostics) {
        this(accepted, edge, "", diagnostics);
    }
}
