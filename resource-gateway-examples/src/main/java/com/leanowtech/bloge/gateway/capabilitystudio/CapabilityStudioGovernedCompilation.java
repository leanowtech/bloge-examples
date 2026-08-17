package com.leanowtech.bloge.gateway.capabilitystudio;

import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioGovernedCompilationPlan;

import java.util.Objects;

/**
 * Payload-bearing result of the narrow Capability Studio governed-compilation adapter.
 *
 * <p>The result exposes the exact existing registration plan and its payload-free source map.
 * It does not execute a graph or register anything; callers still use the existing testing
 * control-plane publication boundary.</p>
 */
public record CapabilityStudioGovernedCompilation(
        ScenarioGovernedCompilationPlan plan,
        CapabilityStudioScenarioDatasetSourceMap sourceMap,
        String semanticFingerprint) {

    public CapabilityStudioGovernedCompilation {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(sourceMap, "sourceMap");
        semanticFingerprint = semanticFingerprint == null ? "" : semanticFingerprint.trim();
    }

    /** @return true only when the delegated plan has no blocking diagnostics */
    public boolean compiled() {
        return plan.compiled() && plan.diagnostics().stream().noneMatch(diagnostic -> diagnostic.error());
    }
}
