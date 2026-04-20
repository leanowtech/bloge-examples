package com.leanowtech.bloge.graphengine.server.rest;

import com.leanowtech.bloge.graphengine.ai.GraphAuthoringException;
import com.leanowtech.bloge.graphengine.ai.GraphAuthoringRequest;
import com.leanowtech.bloge.graphengine.ai.GraphAuthoringResult;
import com.leanowtech.bloge.graphengine.ai.GraphAuthoringService;
import com.leanowtech.bloge.graphengine.ai.validate.DslValidationPipeline;
import com.leanowtech.bloge.graphengine.ai.validate.DslValidationResult;
import com.leanowtech.bloge.graphengine.server.rest.dto.GenerateGraphDraftRequest;
import com.leanowtech.bloge.graphengine.server.rest.dto.ValidateDslRequest;
import com.leanowtech.bloge.graphengine.service.GraphEngineServiceErrorCode;
import com.leanowtech.bloge.graphengine.service.GraphEngineServiceException;

import jakarta.validation.Valid;

import org.springframework.lang.Nullable;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the AI authoring pipeline described in the AI-native graph-engine design.
 */
@RestController
@Validated
@RequestMapping("/api/v1/ai")
public class GraphAuthoringController {

    private final DslValidationPipeline validationPipeline;
    private final GraphAuthoringService graphAuthoringService;

    /**
     * Creates one controller instance.
     *
     * @param validationPipeline shared DSL validation pipeline
     * @param graphAuthoringService optional generation service; may be {@code null} when no
     *                              {@code LlmProvider} bean is configured
     */
    public GraphAuthoringController(DslValidationPipeline validationPipeline,
                                    @Nullable GraphAuthoringService graphAuthoringService) {
        this.validationPipeline = validationPipeline;
        this.graphAuthoringService = graphAuthoringService;
    }

    /**
     * Validates one raw BLOGE DSL string without contacting the LLM provider.
     *
     * @param request validation request
     * @return structured validation result
     */
    @PostMapping("/validate")
    public DslValidationResult validateDsl(@Valid @RequestBody ValidateDslRequest request) {
        return validationPipeline.validate(request.dslSource());
    }

    /**
     * Generates one BLOGE draft from natural language and retries with structured repair prompts
     * when validation fails.
     *
     * @param request generation request
     * @return authoring result with every attempt and the final validation outcome
     */
    @PostMapping("/generate")
    public GraphAuthoringResult generateDraft(@Valid @RequestBody GenerateGraphDraftRequest request) {
        if (graphAuthoringService == null) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE,
                    "AI authoring is not configured because no LlmProvider bean is available"
            );
        }
        try {
            return graphAuthoringService.generate(new GraphAuthoringRequest(
                    request.naturalLanguageRequest(),
                    request.model(),
                    request.fewShotExampleCount(),
                    request.maxRepairRounds(),
                    request.temperature(),
                    request.maxTokens()
            ));
        } catch (GraphAuthoringException exception) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE,
                    exception.getMessage()
            );
        }
    }
}
