package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Optional;

/**
 * Append-only persistence boundary for compiler-issued ScenarioPack execution licenses.
 */
public interface CompiledScenarioRehearsalPlanRepository {
    /** Persists a sealed plan or returns the byte-equivalent existing revision. */
    CompiledScenarioRehearsalPlan create(
            CompiledScenarioRehearsalPlan plan);

    /** Finds one exact compiled plan revision inside a complete enterprise scope. */
    Optional<CompiledScenarioRehearsalPlan> find(
            CapabilitySnapshot.Scope scope, String planId, long revision);
}
