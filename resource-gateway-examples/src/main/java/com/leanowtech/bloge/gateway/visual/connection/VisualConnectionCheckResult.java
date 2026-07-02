package com.leanowtech.bloge.gateway.visual.connection;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import java.util.List;

/**
 * Result of checking a proposed visual graph connection.
 *
 * @param accepted true when the proposed edge can be applied
 * @param edge normalized proposed edge
 * @param bindingKey storage key the canvas should use for a data/input binding
 * @param diagnostics schema, endpoint, or graph diagnostics for the proposed edge
 * @param validation full candidate draft validation/readiness after applying the preview connection
 */
public record VisualConnectionCheckResult(
        boolean accepted,
        GraphDraft.DraftEdge edge,
        String bindingKey,
        List<VisualDiagnostic> diagnostics,
        VisualValidationResult validation
) {
    /**
     * Creates a connection check result.
     */
    public VisualConnectionCheckResult {
        bindingKey = bindingKey == null ? "" : bindingKey;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        validation = validation == null ? new VisualValidationResult(false, diagnostics) : validation;
        accepted = edge != null && diagnostics.stream().noneMatch(VisualDiagnostic::error);
    }

    /**
     * Backward-compatible constructor for checks that create input bindings.
     */
    public VisualConnectionCheckResult(boolean accepted,
                                       GraphDraft.DraftEdge edge,
                                       String bindingKey,
                                       List<VisualDiagnostic> diagnostics) {
        this(accepted, edge, bindingKey, diagnostics, new VisualValidationResult(false, diagnostics));
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
