package com.leanowtech.bloge.gateway.solution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

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
        PreparedInvocation prepared = prepare(scopeKey, solutionRef, suppliedInputs);
        return result(execution.invoke(scopeKey, solutionRef, prepared.values()), prepared);
    }

    /**
     * Verifies and freezes all caller Feature envelopes without dispatching an Instruction.
     *
     * <p>This split lets the governed runtime commit an external-effect reservation after token
     * verification but before any instruction can reach a downstream system.</p>
     */
    public PreparedInvocation prepare(String scopeKey, String solutionRef, JsonNode suppliedInputs) {
        SolutionContract solution = requireSolution(scopeKey, solutionRef);
        if (suppliedInputs == null || !suppliedInputs.isObject()) throw invalidInput();
        if (suppliedInputs.size() != solution.inputs().size()
                || !solution.inputs().keySet().stream().allMatch(suppliedInputs::has)) {
            throw invalidInput();
        }
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
        return new PreparedInvocation(solutionRef, values, tokenNonces);
    }

    /** Executes a verified invocation using an internal platform WRITE authority. */
    public InvocationResult invokeControlled(
            String scopeKey,
            PreparedInvocation prepared,
            IntegrationRequestContext platformIdentity) {
        Objects.requireNonNull(prepared, "prepared");
        return result(execution.executeControlledWrite(
                scopeKey, prepared.solutionRef(), prepared.values(), platformIdentity), prepared);
    }

    /** Executes verified values exclusively against an immutable published contract snapshot. */
    public InvocationResult invokePublished(
            String scopeKey,
            PreparedInvocation prepared,
            PublishedSolutionSnapshot snapshot,
            IntegrationRequestContext platformIdentity) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(snapshot, "snapshot");
        if (!prepared.solutionRef().equals(snapshot.solution().solutionRef())) {
            throw new SolutionContractException(
                    "SOLUTION_NOT_PUBLISHED", "The published Solution coordinate does not match.");
        }
        return result(execution.executePublished(
                scopeKey, snapshot, prepared.values(), platformIdentity), prepared);
    }

    private static InvocationResult result(
            SolutionExecutionService.ExecutionResult result, PreparedInvocation prepared) {
        return new InvocationResult(result.result(), result.reasoning(), result.instructionRef(),
                result.rulePath(), prepared.tokenNonces().size());
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

    /**
     * Frozen, already-verified invocation material safe to execute after a durable reservation.
     *
     * @param solutionRef canonical Solution reference
     * @param values raw values stripped from their trust envelopes
     * @param tokenNonces payload-free nonces used to bind write replay protection
     */
    public record PreparedInvocation(String solutionRef, JsonNode values, List<String> tokenNonces) {
        /** Freezes value material and nonce order. */
        public PreparedInvocation {
            solutionRef = solutionRef == null ? "" : solutionRef.trim();
            values = values == null ? null : values.deepCopy();
            tokenNonces = tokenNonces == null ? List.of() : List.copyOf(tokenNonces);
            if (solutionRef.isBlank() || values == null || !values.isObject()) {
                throw new IllegalArgumentException("prepared invocation is incomplete");
            }
        }

        @Override
        public JsonNode values() {
            return values.deepCopy();
        }
    }
}
