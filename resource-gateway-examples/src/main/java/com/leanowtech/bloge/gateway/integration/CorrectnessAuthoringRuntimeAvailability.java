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
        boolean preflightApi
) {
    public static CorrectnessAuthoringRuntimeAvailability unavailable() {
        return new CorrectnessAuthoringRuntimeAvailability(
                false, false, false, false, false, false, false, false, false);
    }
}
