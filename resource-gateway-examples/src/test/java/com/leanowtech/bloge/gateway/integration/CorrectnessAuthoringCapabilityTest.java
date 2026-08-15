package com.leanowtech.bloge.gateway.integration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CorrectnessAuthoringCapabilityTest {

    @Test
    void defaultsToProtocolOnlyWhenNoRuntimeIsAssembled() {
        var capabilities = new ToolStudioIntegrationService(
                null, null, null, null).capabilities().payload();

        assertThat(capabilities.features())
                .containsEntry("correctnessWorkspaceProtocol", true)
                .containsEntry("correctnessWorkspaceApi", false)
                .containsEntry("correctnessFixtureCatalogProtocol", true)
                .containsEntry("correctnessFixtureCatalogApi", false)
                .containsEntry("correctnessFixtureMaterialProtocol", true)
                .containsEntry("correctnessFixtureMaterialApi", false);
        assertThat(capabilities.endpoints())
                .noneMatch(endpoint -> endpoint.path().startsWith("/api/visual/fixture-"));
    }

    @Test
    void advertisesOnlyPhysicallyAssembledCorrectnessSurfaces() {
        var service = new ToolStudioIntegrationService(null, null, null, null);
        service.configureCorrectnessAuthoringRuntime(
                new CorrectnessAuthoringRuntimeAvailability(
                        true, true, false, true, true, false));

        var capabilities = service.capabilities().payload();

        assertThat(capabilities.features())
                .containsEntry("correctnessWorkspaceApi", true)
                .containsEntry("correctnessCoverageApi", true)
                .containsEntry("correctnessOracleAssertionApi", false)
                .containsEntry("correctnessScenarioV2Api", true)
                .containsEntry("correctnessFixtureCatalogApi", true)
                .containsEntry("correctnessFixtureMaterialApi", false);
        assertThat(capabilities.endpoints())
                .anyMatch(endpoint -> endpoint.path().startsWith(
                        "/api/visual/correctness-workspaces/"))
                .anyMatch(endpoint -> endpoint.path().startsWith(
                        "/api/visual/fixture-assets/"))
                .noneMatch(endpoint -> endpoint.path().startsWith(
                        "/api/visual/fixture-materials"));
    }
}
