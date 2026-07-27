package com.leanowtech.bloge.gateway.visual.scenario;

import com.leanowtech.bloge.gateway.testing.api.FixtureBundleRegistrationRequest;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRegistrationRequest;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.List;

/**
 * Deterministic publication plan from one mutable Scenario revision to governed test assets.
 *
 * @param schemaVersion plan protocol version
 * @param compiled whether every source semantic was represented without downgrade
 * @param sourceScenarioDraftSetId mutable source asset id
 * @param sourceRevision exact source revision
 * @param sourceTargetFingerprint exact visual authoring target fingerprint
 * @param contractFingerprint exact Contract fingerprint
 * @param runtimeTarget independently discovered testing-control-plane target
 * @param fixtures one immutable registration request per Scenario
 * @param suite aggregate immutable suite registration request, null when blocked
 * @param diagnostics fail-closed compilation diagnostics
 */
public record ScenarioGovernedCompilationPlan(
        String schemaVersion,
        boolean compiled,
        String sourceScenarioDraftSetId,
        long sourceRevision,
        String sourceTargetFingerprint,
        String contractFingerprint,
        TestExecutionApiRequest.Target runtimeTarget,
        List<CompiledFixture> fixtures,
        TestSuiteRegistrationRequest suite,
        List<VisualDiagnostic> diagnostics
) {
    /** Current deterministic governed-compilation plan version. */
    public static final String SCHEMA_VERSION = "bloge.scenarioGovernedCompilationPlan.v1";

    /** Freezes plan collections and normalizes source coordinates. */
    public ScenarioGovernedCompilationPlan {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        sourceScenarioDraftSetId = normalized(sourceScenarioDraftSetId);
        sourceTargetFingerprint = normalized(sourceTargetFingerprint);
        contractFingerprint = normalized(contractFingerprint);
        fixtures = fixtures == null ? List.of() : List.copyOf(fixtures);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    /**
     * One Scenario's immutable FixtureBundle request and independently computed fingerprint.
     *
     * @param scenarioId source Scenario id
     * @param fingerprint expected registry fingerprint
     * @param request fixture registration request
     */
    public record CompiledFixture(
            String scenarioId,
            String fingerprint,
            FixtureBundleRegistrationRequest request
    ) {
        /** Normalizes source and fingerprint coordinates. */
        public CompiledFixture {
            scenarioId = normalized(scenarioId);
            fingerprint = normalized(fingerprint);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
