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
                .containsEntry("correctnessFixtureMaterialApi", false)
                .containsEntry("correctnessRunProtocol", true)
                .containsEntry("correctnessRunApi", false)
                .containsEntry("correctnessEvidenceCompanionProtocol", true)
                .containsEntry("correctnessEvidenceCompanionApi", false);
        assertThat(capabilities.endpoints())
                .noneMatch(endpoint -> endpoint.path().startsWith("/api/visual/fixture-"));
    }

    @Test
    void advertisesOnlyPhysicallyAssembledCorrectnessSurfaces() {
        var service = new ToolStudioIntegrationService(null, null, null, null);
        service.configureCorrectnessAuthoringRuntime(
                new CorrectnessAuthoringRuntimeAvailability(
                        true, true, false, true, true, false, true, true, true,
                        true, true));

        var capabilities = service.capabilities().payload();

        assertThat(capabilities.features())
                .containsEntry("correctnessWorkspaceApi", true)
                .containsEntry("correctnessCoverageApi", true)
                .containsEntry("correctnessOracleAssertionApi", false)
                .containsEntry("correctnessScenarioV2Api", true)
                .containsEntry("correctnessFixtureCatalogApi", true)
                .containsEntry("correctnessFixtureMaterialApi", false)
                .containsEntry("correctnessCompilationApi", true)
                .containsEntry("correctnessPublicationApi", true)
                .containsEntry("correctnessPreflightApi", true)
                .containsEntry("correctnessRunApi", true)
                .containsEntry("correctnessEvidenceCompanionApi", true);
        assertThat(capabilities.endpoints())
                .anyMatch(endpoint -> endpoint.path().startsWith(
                        "/api/visual/correctness-workspaces/"))
                .anyMatch(endpoint -> endpoint.path().startsWith(
                        "/api/visual/fixture-assets/"))
                .anyMatch(endpoint -> endpoint.path().equals(
                        "/api/visual/correctness-publications:compile-preview"))
                .anyMatch(endpoint -> endpoint.path().equals(
                        "/api/visual/correctness-publications"))
                .anyMatch(endpoint -> endpoint.path().equals(
                        "/api/visual/correctness-runs:preflight"))
                .anyMatch(endpoint -> endpoint.path().equals(
                        "/api/visual/correctness-runs"))
                .anyMatch(endpoint -> endpoint.path().equals(
                        "/api/visual/correctness-runs/{suiteRunId}/evidence-companion"))
                .noneMatch(endpoint -> endpoint.path().startsWith(
                        "/api/visual/fixture-materials"));
    }
}
