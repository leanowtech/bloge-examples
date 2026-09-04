package com.leanowtech.bloge.gateway.solution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.operator.OperatorContext;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Executes the fixed pure Solution projection without performing feature collection.
 *
 * <p>This service is the execution counterpart of {@link SolutionLowering}. It supplies the
 * unforgeable in-process authority consumed by both built-in operators, then invokes Scenario and
 * Instruction dispatch in graph order. Simulation always returns {@code realExternalCalls=0}; a
 * WRITE Instruction is stubbed before the governed channel can be reached.</p>
 */
public final class SolutionExecutionService {
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };

    private final SolutionEntityRegistry registry;
    private final ObjectMapper mapper;
    private final ScenarioCallOperator scenarioCall;
    private final InstructionCallOperator instructionCall;

    /** Creates a fixed two-operator runtime with an explicit governed dispatch channel. */
    public SolutionExecutionService(
            SolutionEntityRegistry registry,
            ObjectMapper mapper,
            InstructionDispatchChannel channel) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.scenarioCall = new ScenarioCallOperator(registry, mapper);
        this.instructionCall = new InstructionCallOperator(registry, channel);
    }

    /**
     * Runs one Solution with WRITE dispatch replaced by the built-in zero-egress stub.
     *
     * @param scopeKey exact server-derived scope
     * @param solutionRef canonical Solution reference
     * @param featureValues already-collected values; this service never fetches them
     * @return result, explanation, decision path and zero-egress evidence
     */
    public ExecutionResult simulate(String scopeKey, String solutionRef, JsonNode featureValues) {
        return execute(scopeKey, solutionRef, featureValues, SolutionExecutionAuthority.Mode.SIMULATE);
    }

    /**
     * Runs a trusted runtime invocation. READ instructions may use the governed dispatch channel;
     * WRITE instructions remain unavailable until the independent WRITE_EXEC boundary authorizes
     * execution and reconciliation.
     */
    public ExecutionResult invoke(String scopeKey, String solutionRef, JsonNode featureValues) {
        return execute(scopeKey, solutionRef, featureValues, SolutionExecutionAuthority.Mode.RUNTIME);
    }

    private ExecutionResult execute(
            String scopeKey,
            String solutionRef,
            JsonNode featureValues,
            SolutionExecutionAuthority.Mode mode) {
        SolutionContract solution;
        try {
            solution = registry.requireSolution(scopeKey, solutionRef);
        } catch (SolutionEntityRegistry.EntityUnavailableException failure) {
            throw new SolutionContractException(
                    "REFERENCE_UNRESOLVED", "A Solution is unavailable.");
        }
        if (featureValues == null || !featureValues.isObject()
                || !iterable(solution.inputs().keySet()).stream().allMatch(featureValues::has)) {
            throw new SolutionContractException(
                    "SOLUTION_INPUT_INVALID", "Required Solution feature values are missing.");
        }
        Map<String, Object> values = mapper.convertValue(featureValues, OBJECT_MAP);
        GraphContext graph = new GraphContext(values);
        graph.put(SolutionExecutionAuthority.CONTEXT_KEY,
                SolutionExecutionAuthority.issue(scopeKey, mode));
        OperatorContext decideContext = new OperatorContext("decide", solution.solutionRef(), graph, 0);
        Map<String, Object> outcome;
        try {
            outcome = scenarioCall.execute(Map.of(
                    "scenarioRef", solution.rootScenarioRef(), "values", values), decideContext);
        } catch (SolutionContractException expected) {
            throw expected;
        } catch (Exception failure) {
            throw new SolutionContractException(
                    "SOLUTION_EXECUTION_FAILED", "Scenario evaluation failed.");
        }
        List<String> rulePath = outcome.get("rulePath") instanceof List<?> raw
                ? raw.stream().map(Objects::toString).toList() : List.of();
        if (!"INSTRUCTION".equals(outcome.get("outletKind"))) {
            String terminal = Objects.toString(outcome.get("terminalKind"), "");
            return new ExecutionResult(
                    Map.of("terminalKind", terminal), "TERMINAL_SCENARIO_OUTLET", "", rulePath, 0);
        }
        Map<String, Object> bindings = outcome.get("bind") instanceof Map<?, ?> raw
                ? raw.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                        entry -> Objects.toString(entry.getKey()), Map.Entry::getValue))
                : Map.of();
        Map<String, Object> instructionOutput;
        try {
            instructionOutput = instructionCall.execute(Map.of(
                    "instructionRef", outcome.get("ref"), "values", bindings),
                    new OperatorContext("dispatch", solution.solutionRef(), graph, 0));
        } catch (SolutionContractException expected) {
            throw expected;
        } catch (Exception failure) {
            throw new SolutionContractException(
                    "SOLUTION_EXECUTION_FAILED", "Instruction dispatch failed.");
        }
        return new ExecutionResult(
                instructionOutput.get("result"), Objects.toString(instructionOutput.get("reasoning"), ""),
                Objects.toString(outcome.get("ref"), ""), rulePath, 0);
    }

    private static <T> List<T> iterable(java.util.Collection<T> values) {
        return List.copyOf(values);
    }

    /** Immutable payload returned by the zero-egress Solution integration boundary. */
    public record ExecutionResult(
            Object result,
            String reasoning,
            String instructionRef,
            List<String> rulePath,
            int realExternalCalls
    ) {
        /** Freezes path and normalizes stable text. */
        public ExecutionResult {
            reasoning = reasoning == null ? "" : reasoning;
            instructionRef = instructionRef == null ? "" : instructionRef;
            rulePath = rulePath == null ? List.of() : List.copyOf(rulePath);
            if (realExternalCalls != 0) {
                throw new IllegalArgumentException("simulation cannot report external calls");
            }
        }
    }
}
