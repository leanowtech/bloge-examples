package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.solution.FeatureContract;
import com.leanowtech.bloge.gateway.solution.InstructionContract;
import com.leanowtech.bloge.gateway.solution.PublishedSolutionSnapshot;
import com.leanowtech.bloge.gateway.solution.ScenarioContract;
import com.leanowtech.bloge.gateway.solution.SolutionContract;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Computes the immutable identity of the complete executable closure used by a Solution. */
final class SolutionImplementationIdentity {
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    private SolutionImplementationIdentity() { }

    /**
     * Fingerprints current Scenario rules and Instruction bindings.
     *
     * <p>The business contract fingerprint deliberately excludes mutable bindings. This second
     * coordinate closes that gap for the executable closure: rebinding a Feature evaluator,
     * editing a nested Scenario or changing any Instruction invalidates old evidence, signoff and
     * publication even when the top-level Solution document did not change.</p>
     */
    static String fingerprint(
            SolutionEntityRegistry registry,
            ObjectMapper mapper,
            String scope,
            SolutionContract solution) {
        return fingerprint(mapper, snapshot(registry, scope, solution));
    }

    /**
     * Materializes the complete executable closure with one registry read per entity.
     *
     * <p>The returned value is detached from the authoring registry. Callers can therefore bind
     * an idempotency reservation to its fingerprint and execute the same contracts even when a
     * concurrent authoring request creates a newer revision.</p>
     */
    static PublishedSolutionSnapshot snapshot(
            SolutionEntityRegistry registry, String scope, SolutionContract solution) {
        LinkedHashMap<String, FeatureContract> features = new LinkedHashMap<>();
        new TreeSet<>(solution.inputs().values()).forEach(
                ref -> features.put(ref, registry.requireFeature(scope, ref)));
        LinkedHashMap<String, ScenarioContract> scenarios = new LinkedHashMap<>();
        collectScenarios(registry, scope, solution.rootScenarioRef(), scenarios);

        TreeSet<String> instructionRefs = new TreeSet<>(solution.instructions());
        scenarios.values().forEach(scenario -> instructionRefs.addAll(scenario.referencedInstructions()));
        LinkedHashMap<String, InstructionContract> instructions = new LinkedHashMap<>();
        for (String ref : instructionRefs) {
            InstructionContract contract = registry.requireInstruction(scope, ref);
            instructions.put(ref, contract);
        }
        return new PublishedSolutionSnapshot(solution, features, scenarios, instructions);
    }

    /** Computes the same identity directly from the immutable publication snapshot. */
    static String fingerprint(ObjectMapper mapper, PublishedSolutionSnapshot snapshot) {
        List<Map<String, Object>> features = snapshot.features().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getValue().implementationIdentity()).toList();
        List<Map<String, Object>> scenarios = snapshot.scenarios().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getValue().implementationIdentity()).toList();
        List<Map<String, Object>> instructions = snapshot.instructions().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getValue().implementationIdentity()).toList();
        return VisualBundleFingerprint.fromCanonicalValue(mapper, Map.of(
                "solutionRef", snapshot.solution().solutionRef(),
                "features", features,
                "scenarios", scenarios,
                "instructions", instructions), MAX_BYTES);
    }

    private static void collectScenarios(SolutionEntityRegistry registry,
                                         String scope,
                                         String ref,
                                         Map<String, ScenarioContract> scenarios) {
        if (scenarios.containsKey(ref)) return;
        ScenarioContract scenario = registry.requireScenario(scope, ref);
        scenarios.put(ref, scenario);
        new TreeSet<>(scenario.referencedScenarios()).forEach(
                child -> collectScenarios(registry, scope, child, scenarios));
    }

}
