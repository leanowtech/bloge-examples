package com.leanowtech.bloge.graphengine.ai.prompt;

import java.util.List;

/**
 * Fully assembled prompt context for one authoring request.
 *
 * @param syntaxReference raw DSL syntax reference bundled into the prompt
 * @param operatorCatalog prompt-facing catalog of available operators
 * @param fewShotExamples selected few-shot examples
 * @param systemPrompt rendered system prompt sent to the LLM provider
 */
public record PromptContext(
        String syntaxReference,
        List<OperatorCatalogEntry> operatorCatalog,
        List<FewShotExample> fewShotExamples,
        String systemPrompt
) {
    public PromptContext {
        if (syntaxReference == null || syntaxReference.isBlank()) {
            throw new IllegalArgumentException("syntaxReference must not be blank");
        }
        operatorCatalog = operatorCatalog == null ? List.of() : List.copyOf(operatorCatalog);
        fewShotExamples = fewShotExamples == null ? List.of() : List.copyOf(fewShotExamples);
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("systemPrompt must not be blank");
        }
    }
}
