package com.leanowtech.bloge.gateway.solution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Verifies all Solution inputs before crossing into the pure two-operator runtime. */
public final class SolutionInvocationService {
    private final SolutionEntityRegistry registry;
    private final FeatureValueTokenService tokens;
    private final SolutionExecutionService execution;
    private final ObjectMapper mapper;

    /** Creates the runtime boundary from canonical registries, token trust, and instruction dispatch. */
    public SolutionInvocationService(
            SolutionEntityRegistry registry,
            FeatureValueTokenService tokens,
            SolutionExecutionService execution,
            ObjectMapper mapper) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.execution = Objects.requireNonNull(execution, "execution");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Returns the feature-collection plan and mandatory result-plus-reasoning output contract. */
    public ContractView contract(String scopeKey, String solutionRef) {
        SolutionContract solution = requireSolution(scopeKey, solutionRef);
        List<InputView> inputs = new ArrayList<>();
        solution.inputs().forEach((name, featureRef) -> {
            FeatureContract feature;
            try {
                feature = registry.requireFeature(scopeKey, featureRef);
            } catch (SolutionEntityRegistry.EntityUnavailableException failure) {
                throw new SolutionContractException("REFERENCE_UNRESOLVED", "A Feature is unavailable.");
            }
            inputs.add(new InputView(name, feature.featureRef(), feature.evaluationKind().name(),
                    feature.determinism().name(), feature.inputs(), feature.output()));
        });
        return new ContractView(solution.solutionRef(), solution.problem(), inputs,
                Map.of("result", "structured", "reasoning", "required"));
    }

    /**
     * Validates every caller-supplied value envelope, then invokes the pure Solution.
     *
     * <p>Platform-evaluated inputs require an exact token binding to feature ref, evaluation
     * inputs, value, and scope. Interactive inputs accept only an explicit USER source marker;
     * caller tokens cannot upgrade an interactive fact into a platform fact.</p>
     */
    public InvocationResult invoke(String scopeKey, String solutionRef, JsonNode suppliedInputs) {
        SolutionContract solution = requireSolution(scopeKey, solutionRef);
        if (suppliedInputs == null || !suppliedInputs.isObject()) throw invalidInput();
        ObjectNode values = mapper.createObjectNode();
        List<String> tokenNonces = new ArrayList<>();
        solution.inputs().forEach((name, featureRef) -> {
            JsonNode supplied = suppliedInputs.path(name);
            if (!supplied.isObject() || !supplied.has("value")) throw invalidInput();
            FeatureContract feature;
            try {
                feature = registry.requireFeature(scopeKey, featureRef);
            } catch (SolutionEntityRegistry.EntityUnavailableException failure) {
                throw new SolutionContractException("REFERENCE_UNRESOLVED", "A Feature is unavailable.");
            }
            JsonNode value = supplied.path("value");
            if (!SolutionValueSchemaValidator.featureValueMatches(feature.output(), value)) {
                throw invalidInput();
            }
            if (feature.evaluationKind().interactive()) {
                if (!"USER".equals(supplied.path("source").asText())) throw invalidInput();
            } else {
                JsonNode evaluationInputs = supplied.path("inputs");
                if (!evaluationInputs.isObject()) throw invalidInput();
                FeatureValueTokenService.VerifiedToken verified = tokens.verify(
                        supplied.path("evaluationToken").asText(), feature.featureRef(),
                        evaluationInputs, value, scopeKey);
                tokenNonces.add(verified.nonce());
            }
            values.set(name, value.deepCopy());
        });
        SolutionExecutionService.ExecutionResult result = execution.invoke(scopeKey, solutionRef, values);
        return new InvocationResult(result.result(), result.reasoning(), result.instructionRef(),
                result.rulePath(), tokenNonces.size());
    }

    private SolutionContract requireSolution(String scopeKey, String solutionRef) {
        try {
            return registry.requireSolution(scopeKey, solutionRef);
        } catch (SolutionEntityRegistry.EntityUnavailableException failure) {
            throw new SolutionContractException("REFERENCE_UNRESOLVED", "A Solution is unavailable.");
        }
    }

    private static SolutionContractException invalidInput() {
        return new SolutionContractException(
                "SOLUTION_INPUT_INVALID", "The Solution input envelope is invalid.");
    }

    /** One input in the Agent collection plan. */
    public record InputView(
            String name,
            String featureRef,
            String evaluationKind,
            String determinism,
            JsonNode evaluationInputs,
            JsonNode output) {
        /** Freezes JSON schemas exposed by the registry. */
        public InputView {
            evaluationInputs = evaluationInputs.deepCopy();
            output = output.deepCopy();
        }
    }

    /** Immutable public function contract for one Solution. */
    public record ContractView(
            String solutionRef,
            String problem,
            List<InputView> inputs,
            Map<String, String> output) {
        /** Freezes ordered inputs and output declarations. */
        public ContractView {
            inputs = List.copyOf(inputs);
            output = Map.copyOf(output);
        }
    }

    /** Runtime result plus payload-free proof of how many platform facts were verified. */
    public record InvocationResult(
            Object result,
            String reasoning,
            String instructionRef,
            List<String> rulePath,
            int verifiedFeatureCount) {
        /** Freezes the decision path and normalizes explanation text. */
        public InvocationResult {
            reasoning = reasoning == null ? "" : reasoning;
            instructionRef = instructionRef == null ? "" : instructionRef;
            rulePath = List.copyOf(rulePath);
        }
    }
}
