package com.leanowtech.bloge.graphengine.ai.prompt;

import com.leanowtech.bloge.core.spi.OperatorRegistry;

import java.util.List;
import java.util.Objects;

/**
 * Assembles the syntax reference, available operators, few-shot examples, and rendered system
 * prompt used by the AI authoring loop.
 */
public final class PromptContextBuilder {

    private final String syntaxReference;
    private final OperatorCatalogBuilder operatorCatalogBuilder;
    private final FewShotExampleSelector fewShotExampleSelector;

    /**
     * Creates a builder backed by the packaged AI prompt resources and one operator registry.
     *
     * @param operatorRegistry operator registry exposed to the prompt
     */
    public PromptContextBuilder(OperatorRegistry operatorRegistry) {
        this(
                PromptResourceLoader.loadRequired("ai/bloge-dsl-syntax-reference.md"),
                new OperatorCatalogBuilder(operatorRegistry),
                new FewShotExampleSelector(PromptResourceLoader.loadRequired("ai/few-shot-examples.md"))
        );
    }

    /**
     * Creates a builder with explicit prompt components.
     *
     * @param syntaxReference syntax reference text
     * @param operatorCatalogBuilder operator catalog builder
     * @param fewShotExampleSelector few-shot selector
     */
    public PromptContextBuilder(String syntaxReference,
                                OperatorCatalogBuilder operatorCatalogBuilder,
                                FewShotExampleSelector fewShotExampleSelector) {
        if (syntaxReference == null || syntaxReference.isBlank()) {
            throw new IllegalArgumentException("syntaxReference must not be blank");
        }
        this.syntaxReference = syntaxReference.strip();
        this.operatorCatalogBuilder = Objects.requireNonNull(operatorCatalogBuilder, "operatorCatalogBuilder");
        this.fewShotExampleSelector = Objects.requireNonNull(fewShotExampleSelector, "fewShotExampleSelector");
    }

    /**
     * Builds prompt context for one natural-language request.
     *
     * @param naturalLanguageRequest user request
     * @param fewShotExampleCount number of examples to include
     * @return prompt context
     */
    public PromptContext build(String naturalLanguageRequest, int fewShotExampleCount) {
        Objects.requireNonNull(naturalLanguageRequest, "naturalLanguageRequest");
        if (fewShotExampleCount < 0) {
            throw new IllegalArgumentException("fewShotExampleCount must be >= 0");
        }
        List<OperatorCatalogEntry> operatorCatalog = operatorCatalogBuilder.build();
        List<FewShotExample> examples = fewShotExampleSelector.select(naturalLanguageRequest, fewShotExampleCount);
        String systemPrompt = SystemPromptRenderer.render(syntaxReference, operatorCatalog, examples);
        return new PromptContext(syntaxReference, operatorCatalog, examples, systemPrompt);
    }
}
