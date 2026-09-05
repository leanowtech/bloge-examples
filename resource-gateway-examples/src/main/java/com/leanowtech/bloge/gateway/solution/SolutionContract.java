package com.leanowtech.bloge.gateway.solution;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure function contract that composes feature values, a scenario tree, and instructions.
 *
 * <p>A solution never collects features or waits for interaction. Each input name maps to one
 * declared Feature reference; runtime callers supply the resulting values. The graph projection
 * therefore contains only a {@code scenarioCall} followed by {@code instructionCall} dispatch.</p>
 *
 * @param solutionRef stable solution reference
 * @param problem concise business problem statement
 * @param inputs function input name to Feature reference
 * @param rootScenarioRef root of the bounded decision tree
 * @param instructions instructions that the tree may dispatch
 * @param goldenRef approved-case-set reference used for validation
 * @param businessDefinition structured, implementation-independent solution identity
 * @param display independently revised business discovery and presentation material
 */
public record SolutionContract(
        String solutionRef,
        String problem,
        Map<String, String> inputs,
        String rootScenarioRef,
        List<String> instructions,
        String goldenRef,
        BusinessSolutionSemanticContract businessDefinition,
        @com.fasterxml.jackson.annotation.JsonIgnore BusinessCapabilityDisplay display
) {
    /** Normalizes references and rejects duplicate or incomplete pure-function declarations. */
    public SolutionContract {
        solutionRef = normalized(solutionRef);
        problem = normalized(problem);
        rootScenarioRef = normalized(rootScenarioRef);
        goldenRef = normalized(goldenRef);
        inputs = normalizeMap(inputs);
        instructions = instructions == null
                ? List.of() : instructions.stream().map(SolutionContract::normalized).toList();
        if (solutionRef.isBlank() || problem.isBlank() || inputs.isEmpty()
                || rootScenarioRef.isBlank() || instructions.isEmpty() || goldenRef.isBlank()
                || inputs.keySet().stream().anyMatch(String::isBlank)
                || inputs.values().stream().anyMatch(String::isBlank)
                || instructions.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Solution contract is incomplete");
        }
        Set<String> distinct = new LinkedHashSet<>(instructions);
        if (distinct.size() != instructions.size()) {
            throw new IllegalArgumentException("Solution instruction references must be unique");
        }
        businessDefinition = businessDefinition == null
                ? BusinessSolutionSemanticContract.legacy(solutionRef, inputs.keySet().stream().toList())
                : businessDefinition;
        display = display == null
                ? BusinessCapabilityDisplay.legacy(problem, businessDefinition.intent()) : display;
    }

    /** Preserves v1.4.6 callers while deriving a compatibility display. */
    public SolutionContract(String solutionRef, String problem, Map<String, String> inputs,
                            String rootScenarioRef, List<String> instructions, String goldenRef,
                            BusinessSolutionSemanticContract businessDefinition) {
        this(solutionRef, problem, inputs, rootScenarioRef, instructions, goldenRef,
                businessDefinition, null);
    }

    /** Preserves contracts authored before structured Solution business semantics. */
    public SolutionContract(String solutionRef, String problem, Map<String, String> inputs,
                            String rootScenarioRef, List<String> instructions, String goldenRef) {
        this(solutionRef, problem, inputs, rootScenarioRef, instructions, goldenRef, null);
    }

    /** @return canonical implementation-independent identity for GOLDEN drift detection */
    public Map<String, Object> contractIdentity() {
        return Map.of(
                "solutionRef", solutionRef,
                "problem", problem,
                "inputs", inputs,
                "rootScenarioRef", rootScenarioRef,
                "instructions", instructions,
                "goldenRef", goldenRef,
                "businessDefinition", businessDefinition);
    }

    /** @return executable Solution identity excluding independently revised display material */
    public Map<String, Object> implementationIdentity() {
        return contractIdentity();
    }

    /** Returns the same Solution with independently revised discovery material. */
    public SolutionContract withDisplay(BusinessCapabilityDisplay revisedDisplay) {
        return new SolutionContract(solutionRef, problem, inputs, rootScenarioRef, instructions,
                goldenRef, businessDefinition, revisedDisplay);
    }

    private static Map<String, String> normalizeMap(Map<String, String> values) {
        if (values == null) return Map.of();
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        values.forEach((key, value) -> normalized.put(normalized(key), normalized(value)));
        return Map.copyOf(normalized);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
