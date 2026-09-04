package com.leanowtech.bloge.gateway.solution;

import com.fasterxml.jackson.databind.JsonNode;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

/** One governed runtime adapter for an API, DAG, model, or Instruction-result Feature. */
public interface FeatureEvaluationAdapter {
    /** @return the exact evaluation kind owned by this adapter */
    FeatureContract.EvaluationKind kind();

    /** @return whether this adapter owns the versioned evaluation reference */
    boolean supports(String evaluationRef);

    /** Evaluates one contract after kind and reference ownership have been resolved uniquely. */
    JsonNode evaluate(FeatureContract feature, JsonNode inputs, IntegrationRequestContext identity);
}
