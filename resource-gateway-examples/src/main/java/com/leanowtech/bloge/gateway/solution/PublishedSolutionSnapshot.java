package com.leanowtech.bloge.gateway.solution;

import java.util.Map;

/**
 * Immutable executable closure frozen into one governed Solution publication.
 *
 * <p>Runtime dispatch reads this value instead of mutable authoring registries, so a concurrent
 * Scenario or Instruction edit cannot change the business rule or binding after publication
 * validation and before an external effect.</p>
 *
 * @param solution exact published top-level contract
 * @param features complete Feature closure keyed by canonical reference
 * @param scenarios complete root-to-leaf Scenario closure keyed by canonical reference
 * @param instructions complete Instruction closure keyed by canonical reference
 */
public record PublishedSolutionSnapshot(
        SolutionContract solution,
        Map<String, FeatureContract> features,
        Map<String, ScenarioContract> scenarios,
        Map<String, InstructionContract> instructions) {
    /** Preserves snapshots written before Feature implementations joined the frozen closure. */
    public PublishedSolutionSnapshot(
            SolutionContract solution,
            Map<String, ScenarioContract> scenarios,
            Map<String, InstructionContract> instructions) {
        this(solution, Map.of(), scenarios, instructions);
    }

    /** Freezes maps and rejects an incomplete executable closure. */
    public PublishedSolutionSnapshot {
        features = features == null ? Map.of() : Map.copyOf(features);
        scenarios = scenarios == null ? Map.of() : Map.copyOf(scenarios);
        instructions = instructions == null ? Map.of() : Map.copyOf(instructions);
        if (solution == null || scenarios.isEmpty() || !scenarios.containsKey(solution.rootScenarioRef())) {
            throw new IllegalArgumentException("published Solution snapshot is incomplete");
        }
    }
}
