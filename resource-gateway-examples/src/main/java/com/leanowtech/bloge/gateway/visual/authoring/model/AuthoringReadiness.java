package com.leanowtech.bloge.gateway.visual.authoring.model;

import java.util.List;

/**
 * Stage-aware readiness projection for a Quick authoring preview.
 */
public record AuthoringReadiness(
        String state,
        boolean importable,
        boolean strongSchemaReady,
        boolean designReady,
        boolean productionReady,
        List<Gate> gates
) {
    public AuthoringReadiness {
        state = state == null || state.isBlank() ? "INVALID" : state.trim().toUpperCase();
        gates = gates == null ? List.of() : List.copyOf(gates);
        importable = importable && gates.stream().noneMatch(Gate::blocking);
        designReady = designReady && importable;
        productionReady = productionReady && designReady;
    }

    public record Gate(
            String code,
            String level,
            String message,
            String authoringPath,
            boolean blocking
    ) {
        public Gate {
            code = code == null ? "" : code;
            level = level == null || level.isBlank() ? "INFO" : level.toUpperCase();
            message = message == null ? "" : message;
            authoringPath = authoringPath == null || authoringPath.isBlank() ? "/" : authoringPath;
        }
    }
}
