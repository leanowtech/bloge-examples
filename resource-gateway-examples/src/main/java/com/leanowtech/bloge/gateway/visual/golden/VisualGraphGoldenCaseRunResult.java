package com.leanowtech.bloge.gateway.visual.golden;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunResponse;

import java.util.List;

/**
 * Result of executing a golden case.
 *
 * @param passed whether the run succeeded and the selected output matched
 * @param goldenCase golden case definition
 * @param run visual graph run response
 * @param diagnostics golden-case diagnostics
 */
public record VisualGraphGoldenCaseRunResult(
        boolean passed,
        VisualGraphGoldenCase goldenCase,
        VisualGraphRunResponse run,
        List<VisualDiagnostic> diagnostics
) {
    /**
     * Creates a result.
     */
    public VisualGraphGoldenCaseRunResult {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
