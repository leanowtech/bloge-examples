package com.leanowtech.bloge.gateway.visual.codegen;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.List;

/**
 * Result of lowering a visual graph draft to BLOGE DSL.
 *
 * @param generated whether DSL was generated without blocking errors
 * @param dsl generated DSL
 * @param diagnostics lowering diagnostics
 */
public record DslGenerationResult(
        boolean generated,
        String dsl,
        List<VisualDiagnostic> diagnostics
) {
    /**
     * Creates a code generation result.
     */
    public DslGenerationResult {
        dsl = dsl == null ? "" : dsl;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        generated = diagnostics.stream().noneMatch(VisualDiagnostic::error);
    }
}
