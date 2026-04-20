package com.leanowtech.bloge.graphengine.ai.validate;

import com.leanowtech.bloge.graphengine.model.GraphExecutionMode;

import java.util.List;
import java.util.Objects;

/**
 * Validation outcome for one DSL candidate.
 *
 * @param dslSource normalized DSL source that was validated
 * @param executionMode detected top-level execution mode
 * @param declaredRootName detected graph/session/state-machine name
 * @param diagnostics structured validation diagnostics
 * @param qualityScore quality benchmark summary for the source
 * @param valid whether the candidate is free of blocking diagnostics
 */
public record DslValidationResult(
        String dslSource,
        GraphExecutionMode executionMode,
        String declaredRootName,
        List<DslDiagnostic> diagnostics,
        QualityScoreSummary qualityScore,
        boolean valid
) {
    public DslValidationResult {
        if (dslSource == null || dslSource.isBlank()) {
            throw new IllegalArgumentException("dslSource must not be blank");
        }
        executionMode = Objects.requireNonNullElse(executionMode, GraphExecutionMode.GRAPH);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        qualityScore = Objects.requireNonNull(qualityScore, "qualityScore");
    }
}
