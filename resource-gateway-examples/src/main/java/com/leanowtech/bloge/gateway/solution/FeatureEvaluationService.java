package com.leanowtech.bloge.gateway.solution;

import com.fasterxml.jackson.databind.JsonNode;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import java.util.Objects;

/** Evaluates non-interactive Features and signs the exact returned fact for later invocation. */
public final class FeatureEvaluationService {
    private final SolutionEntityRegistry registry;
    private final FeatureEvaluationBackend backend;
    private final FeatureValueTokenService tokens;

    /** Creates the trust boundary over canonical contracts, a governed backend, and token keys. */
    public FeatureEvaluationService(
            SolutionEntityRegistry registry,
            FeatureEvaluationBackend backend,
            FeatureValueTokenService tokens) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
    }

    /** Evaluates one deterministic or version-anchored Feature; interactive collection is refused. */
    public EvaluationResult evaluate(
            String scopeKey, String featureRef, JsonNode inputs, IntegrationRequestContext identity) {
        FeatureContract feature;
        try {
            feature = registry.requireFeature(scopeKey, featureRef);
        } catch (SolutionEntityRegistry.EntityUnavailableException failure) {
            throw new SolutionContractException("REFERENCE_UNRESOLVED", "A Feature is unavailable.");
        }
        if (feature.evaluationKind().interactive()) {
            throw new SolutionContractException(
                    "USE_NATIVE_INTERACTION", "Collect this Feature through the declared user interaction.");
        }
        if (feature.speccing()) {
            throw new SolutionContractException(
                    "FEATURE_BINDING_REQUIRED", "The Feature evaluation binding is unavailable.");
        }
        JsonNode safeInputs = inputs == null ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                : inputs.deepCopy();
        if (!SolutionValueSchemaValidator.inputObjectMatches(feature.inputs(), safeInputs)) {
            throw new SolutionContractException(
                    "FEATURE_INPUT_INVALID", "Feature evaluation inputs do not match the contract.");
        }
        JsonNode value;
        try {
            value = backend.evaluate(feature, safeInputs, identity);
        } catch (SolutionContractException expected) {
            throw expected;
        } catch (RuntimeException failure) {
            throw new SolutionContractException(
                    "FEATURE_EVALUATION_FAILED", "Feature evaluation failed.");
        }
        if (value == null || value.isMissingNode()) {
            throw new SolutionContractException(
                    "FEATURE_EVALUATION_FAILED", "Feature evaluation produced no value.");
        }
        if (!SolutionValueSchemaValidator.featureValueMatches(feature.output(), value)) {
            throw new SolutionContractException(
                    "FEATURE_OUTPUT_INVALID", "Feature evaluation output does not match the contract.");
        }
        return new EvaluationResult(value,
                tokens.issue(feature.featureRef(), safeInputs, value, scopeKey), feature.evaluationKind().name());
    }

    /** Immutable value plus the short-lived trust proof bound to it. */
    public record EvaluationResult(JsonNode value, String evaluationToken, String evaluationKind) {
        /** Freezes the evaluated value at the signing boundary. */
        public EvaluationResult {
            value = value == null ? null : value.deepCopy();
        }

        @Override
        public JsonNode value() {
            return value == null ? null : value.deepCopy();
        }
    }
}
