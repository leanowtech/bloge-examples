package com.leanowtech.bloge.gateway.visual.codegen;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import java.util.List;

/**
 * Result of lowering a visual graph draft to BLOGE DSL.
 *
 * @param generated whether DSL was generated without blocking errors
 * @param dsl generated DSL
 * @param diagnostics lowering diagnostics
 * @param validation draft validation and readiness used before lowering when available
 */
public record DslGenerationResult(
        boolean generated,
        String dsl,
        List<VisualDiagnostic> diagnostics,
        VisualValidationResult validation
) {
    /**
     * Creates a code generation result.
     */
    public DslGenerationResult {
        dsl = dsl == null ? "" : dsl;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        generated = diagnostics.stream().noneMatch(VisualDiagnostic::error);
        validation = validation == null ? new VisualValidationResult(false, diagnostics) : validation;
    }

    /**
     * Backward-compatible constructor for callers that only report lowering diagnostics.
     */
    public DslGenerationResult(boolean generated, String dsl, List<VisualDiagnostic> diagnostics) {
        this(generated, dsl, diagnostics, new VisualValidationResult(false, diagnostics));
    }
}
