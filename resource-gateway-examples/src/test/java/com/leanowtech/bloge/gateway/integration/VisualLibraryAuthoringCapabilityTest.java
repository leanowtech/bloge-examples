package com.leanowtech.bloge.gateway.integration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VisualLibraryAuthoringCapabilityTest {

    @Test
    void advertisesOnlyTheImplementedStageZeroAuthoringSurface() {
        IntegrationCapabilities capabilities = IntegrationCapabilities.current();

        assertThat(capabilities.supportedObjects())
                .containsEntry("visualLibraryAuthoringDocument",
                        java.util.List.of("bloge.visualLibraryAuthoring.v1"))
                .containsEntry("visualLibraryAuthoringCompileResult",
                        java.util.List.of("bloge.visualLibraryCompileResult.v1"))
                .containsEntry("visualLibraryAuthoringProblem",
                        java.util.List.of("bloge.visualAuthoringProblem.v1"));
        assertThat(capabilities.features())
                .containsEntry("visualLibraryAuthoringProtocol", true)
                .containsEntry("visualLibraryAuthoringStatelessPreview", true)
                .containsEntry("functionOnlyLibrary", true)
                .containsEntry("visualLibraryAuthoringInference", false)
                .containsEntry("visualLibraryAuthoringDraftLifecycle", false);
        assertThat(capabilities.endpoints()).contains(
                new IntegrationCapabilities.Endpoint(
                        "POST", "/admin/visual-operator-library-authoring/preview"),
                new IntegrationCapabilities.Endpoint(
                        "POST", "/admin/visual-operator-library-authoring/signature/parse"),
                new IntegrationCapabilities.Endpoint(
                        "GET", "/admin/visual-operator-library-authoring/catalogs")
        );
    }
}
