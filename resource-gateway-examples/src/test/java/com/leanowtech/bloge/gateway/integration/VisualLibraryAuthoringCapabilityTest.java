package com.leanowtech.bloge.gateway.integration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VisualLibraryAuthoringCapabilityTest {

    @Test
    void advertisesTheImplementedAuthoringProtocolAndDraftLifecycle() {
        IntegrationCapabilities capabilities = IntegrationCapabilities.current();

        assertThat(capabilities.supportedObjects())
                .containsEntry("visualLibraryAuthoringDocument",
                        java.util.List.of("bloge.visualLibraryAuthoring.v1"))
                .containsEntry("visualLibraryAuthoringCompileResult",
                        java.util.List.of("bloge.visualLibraryCompileResult.v1"))
                .containsEntry("visualLibraryAuthoringProblem",
                        java.util.List.of("bloge.visualAuthoringProblem.v1"))
                .containsEntry("visualLibraryAuthoringDraft",
                        java.util.List.of("bloge.visualLibraryAuthoringDraft.v1"))
                .containsEntry("visualLibraryAuthoringCommitResult",
                        java.util.List.of("bloge.visualLibraryAuthoringCommitResult.v1"));
        assertThat(capabilities.features())
                .containsEntry("visualLibraryAuthoringProtocol", true)
                .containsEntry("visualLibraryAuthoringStatelessPreview", true)
                .containsEntry("functionOnlyLibrary", true)
                .containsEntry("visualLibraryAuthoringInference", false)
                .containsEntry("visualLibraryAuthoringDraftLifecycle", true)
                .containsEntry("visualLibraryAuthoringEtagConcurrency", true)
                .containsEntry("visualLibraryAuthoringPreviewFencedCommit", true);
        assertThat(capabilities.endpoints()).contains(
                new IntegrationCapabilities.Endpoint(
                        "POST", "/admin/visual-operator-library-authoring/preview"),
                new IntegrationCapabilities.Endpoint(
                        "POST", "/admin/visual-operator-library-authoring/signature/parse"),
                new IntegrationCapabilities.Endpoint(
                        "GET", "/admin/visual-operator-library-authoring/catalogs"),
                new IntegrationCapabilities.Endpoint(
                        "PUT", "/admin/visual-operator-library-authoring/drafts/{draftId}"),
                new IntegrationCapabilities.Endpoint(
                        "POST", "/admin/visual-operator-library-authoring/drafts/{draftId}/preview"),
                new IntegrationCapabilities.Endpoint(
                        "POST", "/admin/visual-operator-library-authoring/drafts/{draftId}/commit")
        );
    }
}
