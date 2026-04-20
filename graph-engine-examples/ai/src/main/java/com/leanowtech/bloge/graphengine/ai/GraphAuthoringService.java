package com.leanowtech.bloge.graphengine.ai;

import com.leanowtech.bloge.graphengine.ai.prompt.FewShotExample;
import com.leanowtech.bloge.graphengine.ai.prompt.PromptContext;
import com.leanowtech.bloge.graphengine.ai.prompt.PromptContextBuilder;
import com.leanowtech.bloge.graphengine.ai.repair.DslSourceNormalizer;
import com.leanowtech.bloge.graphengine.ai.repair.RepairPromptBuilder;
import com.leanowtech.bloge.graphengine.ai.validate.DslValidationResult;
import com.leanowtech.bloge.graphengine.ai.validate.DiagnosticFormatter;
import com.leanowtech.bloge.graphengine.ai.validate.DslValidationPipeline;
import com.leanowtech.bloge.operators.spi.LlmProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * End-to-end AI authoring loop that assembles prompt context, calls an LLM provider, validates
 * the returned DSL, and performs up to two structured repair retries.
 */
public final class GraphAuthoringService {

    private final LlmProvider llmProvider;
    private final PromptContextBuilder promptContextBuilder;
    private final DslValidationPipeline validationPipeline;

    /**
     * Creates a new service instance.
     *
     * @param llmProvider provider used for generation and repair calls
     * @param promptContextBuilder prompt-context builder backed by syntax/operator/example resources
     * @param validationPipeline validation pipeline applied to every candidate
     */
    public GraphAuthoringService(LlmProvider llmProvider,
                                 PromptContextBuilder promptContextBuilder,
                                 DslValidationPipeline validationPipeline) {
        this.llmProvider = Objects.requireNonNull(llmProvider, "llmProvider");
        this.promptContextBuilder = Objects.requireNonNull(promptContextBuilder, "promptContextBuilder");
        this.validationPipeline = Objects.requireNonNull(validationPipeline, "validationPipeline");
    }

    /**
     * Generates one DSL candidate from a natural-language request and repairs it when validation
     * reports blocking diagnostics.
     *
     * @param request authoring request
     * @return final authoring outcome
     */
    public GraphAuthoringResult generate(GraphAuthoringRequest request) {
        Objects.requireNonNull(request, "request");
        PromptContext promptContext = promptContextBuilder.build(
                request.naturalLanguageRequest(),
                request.fewShotExampleCount()
        );
        List<GraphAuthoringAttempt> attempts = new ArrayList<>();

        LlmProvider.LlmResponse initialResponse = invoke(
                request,
                List.of(
                        new LlmProvider.LlmMessage("system", promptContext.systemPrompt()),
                        new LlmProvider.LlmMessage("user",
                                RepairPromptBuilder.buildGenerationPrompt(request.naturalLanguageRequest()))
                )
        );
        String dslSource = DslSourceNormalizer.normalize(initialResponse.content());
        DslValidationResult validation = validationPipeline.validate(dslSource);
        attempts.add(toAttempt(GraphAuthoringPhase.GENERATE, 0, dslSource, initialResponse, validation));

        for (int round = 1; round <= request.maxRepairRounds() && !validation.valid(); round++) {
            String repairPrompt = RepairPromptBuilder.buildRepairPrompt(
                    request.naturalLanguageRequest(),
                    dslSource,
                    DiagnosticFormatter.format(validation.diagnostics())
            );
            LlmProvider.LlmResponse repairResponse = invoke(
                    request,
                    List.of(
                            new LlmProvider.LlmMessage("system", promptContext.systemPrompt()),
                            new LlmProvider.LlmMessage("user", repairPrompt)
                    )
            );
            dslSource = DslSourceNormalizer.normalize(repairResponse.content());
            validation = validationPipeline.validate(dslSource);
            attempts.add(toAttempt(GraphAuthoringPhase.REPAIR, round, dslSource, repairResponse, validation));
        }

        List<String> exampleTitles = promptContext.fewShotExamples().stream()
                .map(FewShotExample::title)
                .toList();
        return new GraphAuthoringResult(
                request.naturalLanguageRequest(),
                request.model(),
                dslSource,
                validation,
                attempts,
                exampleTitles,
                promptContext.operatorCatalog().size(),
                attempts.size() > 1
        );
    }

    /**
     * Exposes the shared DSL validation pipeline so callers can validate user-supplied or
     * previously generated DSL without contacting the LLM provider.
     *
     * @param dslSource candidate DSL source
     * @return validation outcome
     */
    public DslValidationResult validate(String dslSource) {
        return validationPipeline.validate(dslSource);
    }

    private LlmProvider.LlmResponse invoke(GraphAuthoringRequest request,
                                           List<LlmProvider.LlmMessage> messages) {
        try {
            LlmProvider.LlmResponse response = llmProvider.chat(new LlmProvider.LlmRequest(
                    request.model(),
                    messages,
                    request.temperature(),
                    request.maxTokens(),
                    List.of()
            ));
            if (response == null || response.content() == null || response.content().isBlank()) {
                throw new GraphAuthoringException("LLM provider returned empty content");
            }
            return response;
        } catch (GraphAuthoringException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new GraphAuthoringException("LLM provider failed to generate BLOGE DSL", exception);
        }
    }

    private GraphAuthoringAttempt toAttempt(GraphAuthoringPhase phase,
                                            int round,
                                            String dslSource,
                                            LlmProvider.LlmResponse response,
                                            DslValidationResult validation) {
        return new GraphAuthoringAttempt(
                phase,
                round,
                dslSource,
                response.finishReason(),
                response.promptTokens(),
                response.completionTokens(),
                validation
        );
    }
}
