package com.leanowtech.bloge.gateway.visual.connection;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import java.util.List;

/**
 * Result of checking a proposed visual graph connection.
 *
 * @param accepted true when the proposed edge can be applied
 * @param edge normalized proposed edge
 * @param diagnostics schema, endpoint, or graph diagnostics for the proposed edge
 */
public record VisualConnectionCheckResult(
        boolean accepted,
        GraphDraft.DraftEdge edge,
        List<VisualDiagnostic> diagnostics
) {
    /**
     * Creates a connection check result.
     */
    public VisualConnectionCheckResult {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        accepted = edge != null && diagnostics.stream().noneMatch(VisualDiagnostic::error);
    }
}
