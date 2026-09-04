package com.leanowtech.bloge.gateway.solution;

import com.fasterxml.jackson.databind.JsonNode;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

/** Pluggable dispatcher from a declared Feature evaluation kind to its governed runtime. */
@FunctionalInterface
public interface FeatureEvaluationBackend {
    /** Evaluates the exact versioned reference and returns one atomic contract-shaped value. */
    JsonNode evaluate(FeatureContract feature, JsonNode inputs, IntegrationRequestContext identity);
}
