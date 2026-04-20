package com.leanowtech.bloge.graphengine.ai;

import com.leanowtech.bloge.graphengine.ai.validate.DslValidationResult;

import java.util.List;
import java.util.Objects;

/**
 * Final outcome of one AI authoring request.
 *
 * @param naturalLanguageRequest original user request
 * @param model provider model used for authoring
 * @param dslSource final normalized DSL candidate
 * @param validation validation result for the final candidate
 * @param attempts ordered generate/repair attempts
 * @param selectedExampleTitles titles of the few-shot examples used in the prompt
 * @param operatorCatalogSize number of operators exposed to the prompt
 * @param repaired whether at least one repair round was used
 */
public record GraphAuthoringResult(
        String naturalLanguageRequest,
        String model,
        String dslSource,
        DslValidationResult validation,
        List<GraphAuthoringAttempt> attempts,
        List<String> selectedExampleTitles,
        int operatorCatalogSize,
        boolean repaired
) {
    public GraphAuthoringResult {
        if (naturalLanguageRequest == null || naturalLanguageRequest.isBlank()) {
            throw new IllegalArgumentException("naturalLanguageRequest must not be blank");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (dslSource == null || dslSource.isBlank()) {
            throw new IllegalArgumentException("dslSource must not be blank");
        }
        validation = Objects.requireNonNull(validation, "validation");
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
        selectedExampleTitles = selectedExampleTitles == null ? List.of() : List.copyOf(selectedExampleTitles);
        if (operatorCatalogSize < 0) {
            throw new IllegalArgumentException("operatorCatalogSize must be >= 0");
        }
    }
}
