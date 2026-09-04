package com.leanowtech.bloge.gateway.solution;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Fails closed when a Scenario reference graph is cyclic, too deep, unresolved or under-bound.
 *
 * <p>Validation reads canonical contracts from one exact scope and performs no execution. The
 * maximum depth includes the root Scenario. An outlet binding is complete only when it supplies
 * every declared input of its target Scenario or Instruction.</p>
 */
public final class ScenarioTreeValidator {
    private final SolutionEntityRegistry registry;
    private final int maximumDepth;

    /** Creates a validator with an explicit positive recursion bound. */
    public ScenarioTreeValidator(SolutionEntityRegistry registry, int maximumDepth) {
        this.registry = Objects.requireNonNull(registry, "registry");
        if (maximumDepth < 1) throw new IllegalArgumentException("maximumDepth must be positive");
        this.maximumDepth = maximumDepth;
    }

    /**
     * Validates one complete tree from its root.
     *
     * @param scopeKey exact authenticated storage scope
     * @param rootScenarioRef root Scenario reference
     * @return immutable structural summary
     */
    public ValidationResult validate(String scopeKey, String rootScenarioRef) {
        LinkedHashSet<String> scenarios = new LinkedHashSet<>();
        LinkedHashSet<String> instructions = new LinkedHashSet<>();
        int depth = visit(scopeKey, rootScenarioRef, 1, new LinkedHashSet<>(),
                scenarios, instructions);
        return new ValidationResult(true, depth, List.copyOf(scenarios), List.copyOf(instructions));
    }

    private int visit(String scopeKey,
                      String scenarioRef,
                      int depth,
                      Set<String> active,
                      Set<String> scenarios,
                      Set<String> instructions) {
        if (depth > maximumDepth) throw failure(
                "SCENARIO_TREE_TOO_DEEP", "Scenario tree exceeds the configured depth.");
        if (!active.add(scenarioRef)) throw failure(
                "SCENARIO_TREE_CYCLE", "Scenario tree contains a cycle.");
        ScenarioContract scenario = requireScenario(scopeKey, scenarioRef);
        scenarios.add(scenarioRef);
        int maximum = depth;
        for (ScenarioContract.Outlet outlet : outlets(scenario)) {
            if (outlet.kind() == ScenarioContract.OutletKind.SUB_SCENARIO) {
                ScenarioContract target = requireScenario(scopeKey, outlet.ref());
                requireBindings(outlet.bind().keySet(), target.inputs());
                maximum = Math.max(maximum, visit(scopeKey, outlet.ref(), depth + 1,
                        active, scenarios, instructions));
            } else if (outlet.kind() == ScenarioContract.OutletKind.INSTRUCTION) {
                InstructionContract instruction = requireInstruction(scopeKey, outlet.ref());
                requireBindings(outlet.bind().keySet(), fieldNames(instruction.inputs()));
                instructions.add(instruction.instructionRef());
            }
        }
        active.remove(scenarioRef);
        return maximum;
    }

    private ScenarioContract requireScenario(String scopeKey, String ref) {
        try {
            return registry.requireScenario(scopeKey, ref);
        } catch (SolutionEntityRegistry.EntityUnavailableException failure) {
            throw failure("SCENARIO_OUTLET_UNRESOLVED", "A Scenario outlet is unresolved.");
        }
    }

    private InstructionContract requireInstruction(String scopeKey, String ref) {
        try {
            return registry.requireInstruction(scopeKey, ref);
        } catch (SolutionEntityRegistry.EntityUnavailableException failure) {
            throw failure("SCENARIO_OUTLET_UNRESOLVED", "An Instruction outlet is unresolved.");
        }
    }

    private static void requireBindings(Set<String> provided, List<String> required) {
        if (!provided.containsAll(required)) {
            throw failure("SCENARIO_BIND_INCOMPLETE", "A Scenario outlet binding is incomplete.");
        }
    }

    private static List<ScenarioContract.Outlet> outlets(ScenarioContract scenario) {
        ArrayList<ScenarioContract.Outlet> outlets = new ArrayList<>();
        scenario.rules().forEach(rule -> outlets.add(rule.outlet()));
        outlets.add(scenario.otherwise());
        return List.copyOf(outlets);
    }

    private static List<String> fieldNames(com.fasterxml.jackson.databind.JsonNode object) {
        ArrayList<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return List.copyOf(names);
    }

    private static SolutionContractException failure(String code, String message) {
        return new SolutionContractException(code, message);
    }

    /** Immutable graph summary returned after every branch has passed validation. */
    public record ValidationResult(
            boolean acyclic,
            int maxDepth,
            List<String> referencedScenarios,
            List<String> referencedInstructions
    ) {
        /** Freezes deterministic traversal order. */
        public ValidationResult {
            referencedScenarios = List.copyOf(referencedScenarios);
            referencedInstructions = List.copyOf(referencedInstructions);
        }
    }
}
