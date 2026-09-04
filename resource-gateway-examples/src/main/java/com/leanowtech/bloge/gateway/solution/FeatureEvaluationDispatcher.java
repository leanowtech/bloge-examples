package com.leanowtech.bloge.gateway.solution;

import com.fasterxml.jackson.databind.JsonNode;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/** Selects exactly one governed Feature adapter by evaluation kind and versioned reference. */
@Component
public final class FeatureEvaluationDispatcher implements FeatureEvaluationBackend {
    private final List<FeatureEvaluationAdapter> adapters;

    /** Freezes the installed adapter set so one request cannot observe mixed registry generations. */
    public FeatureEvaluationDispatcher(ObjectProvider<FeatureEvaluationAdapter> adapters) {
        this.adapters = adapters.orderedStream().toList();
    }

    /** Fails closed when a binding is missing or ambiguously claimed. */
    @Override
    public JsonNode evaluate(
            FeatureContract feature, JsonNode inputs, IntegrationRequestContext identity) {
        List<FeatureEvaluationAdapter> matches = adapters.stream()
                .filter(adapter -> adapter.kind() == feature.evaluationKind())
                .filter(adapter -> adapter.supports(feature.evaluationRef()))
                .toList();
        if (matches.size() != 1) {
            throw new SolutionContractException(
                    "FEATURE_EVALUATOR_UNAVAILABLE", "Feature evaluation is unavailable.");
        }
        return matches.getFirst().evaluate(feature, inputs, identity);
    }
}
