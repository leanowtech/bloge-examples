package com.leanowtech.bloge.gateway.solution;

import java.util.Map;

/**
 * Immutable aggregate decoded from one {@code bloge.solutionAuthoring.v1} document.
 *
 * <p>The document is an authoring transport only. Canonical revisions remain independent per
 * entity so a Feature implementation binding can evolve without rewriting unrelated Scenarios or
 * Solutions.</p>
 *
 * @param features feature contracts keyed by their exact reference
 * @param scenarios scenario contracts keyed by their exact reference
 * @param instructions instruction contracts keyed by their exact reference
 * @param solutions solution contracts keyed by their exact reference
 */
public record SolutionDocument(
        Map<String, FeatureContract> features,
        Map<String, ScenarioContract> scenarios,
        Map<String, InstructionContract> instructions,
        Map<String, SolutionContract> solutions
) {
    /** Freezes all four entity maps. */
    public SolutionDocument {
        features = features == null ? Map.of() : Map.copyOf(features);
        scenarios = scenarios == null ? Map.of() : Map.copyOf(scenarios);
        instructions = instructions == null ? Map.of() : Map.copyOf(instructions);
        solutions = solutions == null ? Map.of() : Map.copyOf(solutions);
    }
}
