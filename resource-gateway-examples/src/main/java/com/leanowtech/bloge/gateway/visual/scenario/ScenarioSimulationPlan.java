package com.leanowtech.bloge.gateway.visual.scenario;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.simulation.VisualGraphSimulationRequest;

import java.util.List;

/**
 * Deterministic transient-run plan compiled from one Scenario draft.
 *
 * @param schemaVersion plan protocol version
 * @param compiled whether a lossless transient request was produced
 * @param scenarioId exact Scenario id
 * @param targetFingerprint exact target fingerprint validated
 * @param contractFingerprint exact Contract fingerprint validated
 * @param request existing visual simulation request, null when compilation is blocked
 * @param assertions expected-result assertions evaluated after execution
 * @param diagnostics compilation diagnostics
 */
public record ScenarioSimulationPlan(
        String schemaVersion,
        boolean compiled,
        String scenarioId,
        String targetFingerprint,
        String contractFingerprint,
        VisualGraphSimulationRequest request,
        List<ScenarioDraftSet.AssertionDraft> assertions,
        List<VisualDiagnostic> diagnostics
) {
    /** Current transient Scenario simulation-plan version. */
    public static final String SCHEMA_VERSION = "bloge.scenarioSimulationPlan.v1";

    /** Freezes plan collections and derives compiled state from blocking diagnostics. */
    public ScenarioSimulationPlan {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion.trim();
        scenarioId = scenarioId == null ? "" : scenarioId.trim();
        targetFingerprint = targetFingerprint == null ? "" : targetFingerprint.trim();
        contractFingerprint = contractFingerprint == null ? "" : contractFingerprint.trim();
        assertions = assertions == null ? List.of() : List.copyOf(assertions);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        compiled = request != null && diagnostics.stream().noneMatch(VisualDiagnostic::error);
    }
}
