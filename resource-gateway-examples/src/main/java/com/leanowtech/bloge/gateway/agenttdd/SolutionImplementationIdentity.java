package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.solution.InstructionContract;
import com.leanowtech.bloge.gateway.solution.PublishedSolutionSnapshot;
import com.leanowtech.bloge.gateway.solution.ScenarioContract;
import com.leanowtech.bloge.gateway.solution.SolutionContract;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Computes the immutable identity of the complete executable closure used by a Solution. */
final class SolutionImplementationIdentity {
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    private SolutionImplementationIdentity() { }

    /**
     * Fingerprints current Scenario rules and Instruction bindings.
     *
     * <p>The business contract fingerprint deliberately excludes mutable bindings. This second
     * coordinate closes that gap for the executable decision closure: editing a nested Scenario
     * or changing any Instruction invalidates old evidence, signoff and publication even when the
     * top-level Solution document did not change. Feature evaluators remain independently
     * versioned and produce fresh, binding-specific value tokens before each invocation.</p>
     */
    static String fingerprint(
            SolutionEntityRegistry registry,
            ObjectMapper mapper,
            String scope,
            SolutionContract solution) {
        Set<String> scenarioRefs = new TreeSet<>();
        collectScenarios(registry, scope, solution.rootScenarioRef(), scenarioRefs);
        LinkedHashMap<String, ScenarioContract> scenarios = new LinkedHashMap<>();
        scenarioRefs.forEach(ref -> scenarios.put(ref, registry.requireScenario(scope, ref)));

        Set<String> instructionRefs = new TreeSet<>(solution.instructions());
        for (String scenarioRef : scenarioRefs) {
            instructionRefs.addAll(registry.requireScenario(scope, scenarioRef).referencedInstructions());
        }
        LinkedHashMap<String, InstructionContract> instructions = new LinkedHashMap<>();
        for (String ref : instructionRefs) {
            InstructionContract contract = registry.requireInstruction(scope, ref);
            instructions.put(ref, contract);
        }
        return fingerprint(mapper, new PublishedSolutionSnapshot(solution, scenarios, instructions));
    }

    /** Computes the same identity directly from the immutable publication snapshot. */
    static String fingerprint(ObjectMapper mapper, PublishedSolutionSnapshot snapshot) {
        List<ScenarioContract> scenarios = snapshot.scenarios().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList();
        List<InstructionContract> instructions = snapshot.instructions().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList();
        return VisualBundleFingerprint.fromCanonicalValue(mapper, Map.of(
                "solutionRef", snapshot.solution().solutionRef(),
                "scenarios", scenarios,
                "instructions", instructions), MAX_BYTES);
    }

    private static void collectScenarios(
            SolutionEntityRegistry registry, String scope, String ref, Set<String> visited) {
        if (!visited.add(ref)) return;
        ScenarioContract scenario = registry.requireScenario(scope, ref);
        scenario.referencedScenarios().forEach(child -> collectScenarios(registry, scope, child, visited));
    }

}
