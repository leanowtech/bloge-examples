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
                        java.util.List.of("bloge.visualLibraryAuthoringCommitResult.v1"))
                .containsEntry("visualLibrarySampleInferenceRequest",
                        java.util.List.of("bloge.visualSampleInferenceRequest.v1"))
                .containsEntry("visualLibrarySampleInferenceResult",
                        java.util.List.of("bloge.visualSampleInferenceResult.v1"))
                .containsEntry("visualLibrarySampleInferenceApplyRequest",
                        java.util.List.of("bloge.visualSampleInferenceApplyRequest.v1"))
                .containsEntry("visualLibraryAuthoringOperatorTestRun",
                        java.util.List.of(
                                "bloge.visualAuthoringOperatorTestRunRequest.v1",
                                "bloge.visualAuthoringOperatorTestRunEvidence.v1"))
                .containsEntry("visualLibraryAuthoringFunctionTestRun",
                        java.util.List.of(
                                "bloge.visualAuthoringFunctionTestSuite.v1",
                                "bloge.visualAuthoringFunctionTestCase.v1",
                                "bloge.visualAuthoringFunctionTestRunRequest.v1",
                                "bloge.visualAuthoringFunctionTestRunEvidence.v1"))
                .containsEntry("visualLibraryAuthoringFunctionTestWorker",
                        java.util.List.of(
                                "bloge.visualAuthoringFunctionWorkerInvocationRequest.v1",
                                "bloge.visualAuthoringFunctionWorkerInvocationResponse.v1"))
                .containsEntry("visualLibraryAuthoringTestEvidence",
                        java.util.List.of(
                                "bloge.visualAuthoringTestEvidenceRecord.v1",
                                "bloge.visualAuthoringTestEvidenceView.v1"))
                .containsEntry("visualLibraryAuthoringTestGate",
                        java.util.List.of(
                                "bloge.visualAuthoringTestEvidenceGate.v1"))
                .containsEntry("visualLibraryAuthoringFixtureSaveRequest",
                        java.util.List.of(
                                "bloge.visualAuthoringFixtureSaveRequest.v1"))
                .containsEntry("visualLibraryAuthoringFixtureReceipt",
                        java.util.List.of(
                                "bloge.visualAuthoringFixtureReceipt.v1"))
                .containsEntry("visualLibraryAuthoringFixtureMaterial",
                        java.util.List.of(
                                "bloge.visualAuthoringFixtureMaterial.v1"));
        assertThat(capabilities.features())
                .containsEntry("visualLibraryAuthoringProtocol", true)
                .containsEntry("visualLibraryAuthoringStatelessPreview", true)
                .containsEntry("functionOnlyLibrary", true)
                .containsEntry("visualLibraryAuthoringInference", true)
                .containsEntry("visualLibraryAuthoringDraftLifecycle", true)
                .containsEntry("visualLibraryAuthoringEtagConcurrency", true)
                .containsEntry("visualLibraryAuthoringPreviewFencedCommit", true)
                .containsEntry("visualLibraryAuthoringOperatorTestDraftRunner", true)
                .containsEntry("visualLibraryAuthoringFunctionTestDraftRunner", true)
                .containsEntry("visualLibraryAuthoringGovernedFixturePersistence", false)
                .containsEntry("visualLibraryAuthoringIsolatedFunctionTestWorker", true)
                .containsEntry("visualLibraryAuthoringSignedTestEvidence", true)
                .containsEntry("visualLibraryAuthoringTestEvidenceGate", true);
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
                        "POST", "/admin/visual-operator-library-authoring/drafts/{draftId}/infer/samples"),
                new IntegrationCapabilities.Endpoint(
                        "POST", "/admin/visual-operator-library-authoring/drafts/{draftId}/infer/samples/apply"),
                new IntegrationCapabilities.Endpoint(
                        "POST", "/admin/visual-operator-library-authoring/drafts/{draftId}/tests/operators/draft"),
                new IntegrationCapabilities.Endpoint(
                        "POST", "/admin/visual-operator-library-authoring/drafts/{draftId}/tests/operators/run"),
                new IntegrationCapabilities.Endpoint(
                        "POST", "/admin/visual-operator-library-authoring/drafts/{draftId}/tests/functions/draft"),
                new IntegrationCapabilities.Endpoint(
                        "POST", "/admin/visual-operator-library-authoring/drafts/{draftId}/tests/functions/run"),
                new IntegrationCapabilities.Endpoint(
                        "GET", "/admin/visual-operator-library-authoring/drafts/{draftId}/tests/evidence/{runId}"),
                new IntegrationCapabilities.Endpoint(
                        "GET", "/admin/visual-operator-library-authoring/drafts/{draftId}/tests/gate"),
                new IntegrationCapabilities.Endpoint(
                        "POST", "/admin/visual-operator-library-authoring/drafts/{draftId}/commit")
        );
        assertThat(capabilities.endpoints()).doesNotContain(
                new IntegrationCapabilities.Endpoint(
                        "POST",
                        "/admin/visual-operator-library-authoring/drafts/{draftId}/fixtures"),
                new IntegrationCapabilities.Endpoint(
                        "GET",
                        "/admin/visual-operator-library-authoring/fixtures/{fixtureId}"));
    }
}
