package com.leanowtech.bloge.graphengine.ai;

import com.leanowtech.bloge.graphengine.ai.validate.DslValidationResult;

import java.util.Objects;

/**
 * Captures one generate or repair attempt made by the AI authoring service.
 *
 * @param phase attempt phase
 * @param round zero-based attempt counter within the phase loop
 * @param dslSource normalized DSL source returned by the provider
 * @param finishReason provider finish reason, when supplied
 * @param promptTokens provider prompt-token count
 * @param completionTokens provider completion-token count
 * @param validation validation result for the candidate source
 */
public record GraphAuthoringAttempt(
        GraphAuthoringPhase phase,
        int round,
        String dslSource,
        String finishReason,
        int promptTokens,
        int completionTokens,
        DslValidationResult validation
) {
    public GraphAuthoringAttempt {
        phase = Objects.requireNonNull(phase, "phase");
        if (round < 0) {
            throw new IllegalArgumentException("round must be >= 0");
        }
        if (dslSource == null || dslSource.isBlank()) {
            throw new IllegalArgumentException("dslSource must not be blank");
        }
        if (promptTokens < 0) {
            throw new IllegalArgumentException("promptTokens must be >= 0");
        }
        if (completionTokens < 0) {
            throw new IllegalArgumentException("completionTokens must be >= 0");
        }
        validation = Objects.requireNonNull(validation, "validation");
    }
}
