package com.leanowtech.bloge.graphengine.server.rest.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * HTTP payload that asks the AI authoring pipeline to generate one BLOGE draft from a
 * natural-language request.
 *
 * @param naturalLanguageRequest plain-language workflow request
 * @param model provider model identifier
 * @param fewShotExampleCount optional number of few-shot examples to include
 * @param maxRepairRounds optional maximum number of repair retries
 * @param temperature optional generation temperature
 * @param maxTokens optional completion token budget
 */
public record GenerateGraphDraftRequest(
        @NotBlank(message = "naturalLanguageRequest must not be blank")
        String naturalLanguageRequest,
        @NotBlank(message = "model must not be blank")
        String model,
        @Min(value = 0, message = "fewShotExampleCount must be >= 0")
        @Max(value = 5, message = "fewShotExampleCount must be <= 5")
        Integer fewShotExampleCount,
        @Min(value = 0, message = "maxRepairRounds must be >= 0")
        @Max(value = 2, message = "maxRepairRounds must be <= 2")
        Integer maxRepairRounds,
        @DecimalMin(value = "0.0", message = "temperature must be >= 0.0")
        @DecimalMax(value = "2.0", message = "temperature must be <= 2.0")
        Double temperature,
        @Min(value = 1, message = "maxTokens must be >= 1")
        Integer maxTokens
) {
}
