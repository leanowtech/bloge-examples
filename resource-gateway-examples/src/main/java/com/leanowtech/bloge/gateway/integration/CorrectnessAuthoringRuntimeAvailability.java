package com.leanowtech.bloge.gateway.integration;

/** Exact deployment truth for the independently assembled Correctness authoring surfaces. */
public record CorrectnessAuthoringRuntimeAvailability(
        boolean workspaceApi,
        boolean coverageApi,
        boolean oracleAssertionApi,
        boolean scenarioV2Api,
        boolean fixtureCatalogApi,
        boolean fixtureMaterialApi,
        boolean compilationApi,
        boolean publicationApi,
        boolean preflightApi,
        boolean runApi,
        boolean evidenceCompanionApi,
        boolean outcomeCalibrationApi,
        boolean governanceFeedbackApi
) {
    /** Backward-compatible constructor for deployments compiled before COR-09. */
    public CorrectnessAuthoringRuntimeAvailability(
            boolean workspaceApi,
            boolean coverageApi,
            boolean oracleAssertionApi,
            boolean scenarioV2Api,
            boolean fixtureCatalogApi,
            boolean fixtureMaterialApi,
            boolean compilationApi,
            boolean publicationApi,
            boolean preflightApi,
            boolean runApi,
            boolean evidenceCompanionApi
    ) {
        this(workspaceApi, coverageApi, oracleAssertionApi, scenarioV2Api,
                fixtureCatalogApi, fixtureMaterialApi, compilationApi, publicationApi,
                preflightApi, runApi, evidenceCompanionApi, false, false);
    }

    public static CorrectnessAuthoringRuntimeAvailability unavailable() {
        return new CorrectnessAuthoringRuntimeAvailability(
                false, false, false, false, false, false, false, false, false,
                false, false, false, false);
    }
}
