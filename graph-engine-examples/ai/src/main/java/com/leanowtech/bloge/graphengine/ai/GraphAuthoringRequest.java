package com.leanowtech.bloge.graphengine.ai;

/**
 * One AI authoring request that turns a natural-language goal into BLOGE DSL.
 *
 * @param naturalLanguageRequest plain-language workflow goal
 * @param model LLM model identifier to pass to the provider
 * @param fewShotExampleCount optional number of few-shot examples to include
 * @param maxRepairRounds optional maximum number of structured repair retries
 * @param temperature optional generation temperature
 * @param maxTokens optional completion token budget
 */
public record GraphAuthoringRequest(
        String naturalLanguageRequest,
        String model,
        Integer fewShotExampleCount,
        Integer maxRepairRounds,
        Double temperature,
        Integer maxTokens
) {
    public static final int DEFAULT_FEW_SHOT_EXAMPLE_COUNT = 3;
    public static final int DEFAULT_MAX_REPAIR_ROUNDS = 2;

    public GraphAuthoringRequest {
        if (naturalLanguageRequest == null || naturalLanguageRequest.isBlank()) {
            throw new IllegalArgumentException("naturalLanguageRequest must not be blank");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        fewShotExampleCount = fewShotExampleCount == null ? DEFAULT_FEW_SHOT_EXAMPLE_COUNT : fewShotExampleCount;
        maxRepairRounds = maxRepairRounds == null ? DEFAULT_MAX_REPAIR_ROUNDS : maxRepairRounds;
        if (fewShotExampleCount < 0 || fewShotExampleCount > 5) {
            throw new IllegalArgumentException("fewShotExampleCount must be between 0 and 5");
        }
        if (maxRepairRounds < 0 || maxRepairRounds > 2) {
            throw new IllegalArgumentException("maxRepairRounds must be between 0 and 2");
        }
        if (temperature != null && (temperature < 0.0d || temperature > 2.0d)) {
            throw new IllegalArgumentException("temperature must be between 0.0 and 2.0");
        }
        if (maxTokens != null && maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be >= 1");
        }
    }
}
