package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiResponse;
import com.leanowtech.bloge.gateway.testing.api.TestBoundaryCasePlan;
import com.leanowtech.bloge.gateway.testing.api.TestPropertyCasePlan;
import com.leanowtech.bloge.gateway.testing.api.TestMutationCasePlan;
import com.leanowtech.bloge.gateway.testing.api.TestBoundarySuiteMaterializationRequest;
import com.leanowtech.bloge.gateway.testing.api.TestBoundarySuiteMaterializationResponse;
import com.leanowtech.bloge.gateway.testing.api.TestPropertySuiteMaterializationRequest;
import com.leanowtech.bloge.gateway.testing.api.TestPropertySuiteMaterializationResponse;
import com.leanowtech.bloge.gateway.testing.api.TestMutationSuiteMaterializationRequest;
import com.leanowtech.bloge.gateway.testing.api.TestMutationSuiteMaterializationResponse;
import com.leanowtech.bloge.gateway.testing.api.TestMutationSuiteExecutionRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionResponse;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityProgressResponse;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAuthorityRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAuthorityResponse;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobAuthorizer;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobCancelRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobSubmitRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobSubmitResponse;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobView;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionResponse;
import com.leanowtech.bloge.gateway.testing.api.DurableTestOwnerClaimRequest;
import com.leanowtech.bloge.gateway.testing.api.DurableTestOwnerClaimResponse;
import com.leanowtech.bloge.gateway.testing.api.DurableTestWorkerAcquisitionRequest;
import com.leanowtech.bloge.gateway.testing.api.DurableTestWorkerAcquisitionResponse;
import com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionQueryResponse;
import com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCreateRequest;
import com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCreateResponse;
import com.leanowtech.bloge.gateway.testing.api.DurableOperatorTestExecutionCreateRequest;
import com.leanowtech.bloge.gateway.testing.api.DurableTestRecoveryHeartbeatRequest;
import com.leanowtech.bloge.gateway.testing.api.DurableTestRecoveryHeartbeatResponse;
import com.leanowtech.bloge.gateway.testing.api.DurableTestTerminalRecoveryRequest;
import com.leanowtech.bloge.gateway.testing.api.DurableTestTerminalRecoveryResponse;
import com.leanowtech.bloge.gateway.testing.api.DurableTestRecoveryStepRequest;
import com.leanowtech.bloge.gateway.testing.api.DurableTestRecoveryStepResponse;
import com.leanowtech.bloge.gateway.testing.api.DurableTestRecoverySequenceRequest;
import com.leanowtech.bloge.gateway.testing.api.DurableTestRecoverySequenceResponse;
import com.leanowtech.bloge.gateway.testing.api.DurableStateProjectionFindingClaimRequest;
import com.leanowtech.bloge.gateway.testing.api.DurableStateProjectionFindingResolutionResponse;
import com.leanowtech.bloge.gateway.testing.api.DurableWorkerQuarantineClaimRequest;
import com.leanowtech.bloge.gateway.testing.api.DurableWorkerQuarantineDiscardApprovalRequest;
import com.leanowtech.bloge.gateway.testing.api.DurableWorkerQuarantineApprovedDiscardResponse;
import com.leanowtech.bloge.gateway.testing.api.DurableWorkerQuarantineChangeAuthorizationReference;
import com.leanowtech.bloge.gateway.testing.api.DurableWorkerQuarantineResolutionResponse;
import com.leanowtech.bloge.gateway.testing.api.WorkerQuarantineChangeAuthorization;
import com.leanowtech.bloge.gateway.testing.api.WorkerQuarantineChangeAuthorizationBinding;
import com.leanowtech.bloge.gateway.testing.api.WorkerQuarantineChangeAuthorizationTrustStore;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.ExecutionServiceStateSnapshot;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV2;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV3;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV4;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV5;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteEvidenceBundle;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV2;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV3;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV4;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV5;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityEvidence;
import com.leanowtech.bloge.gateway.testing.domain.WorkerQuarantineRequestIndexMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestabilityCapabilitiesTest {

    @Test
    void asynchronousStabilitySubmissionIsAdvertisedOnlyWhenWorkerRuntimeIsEnabled() {
        IntegrationCapabilities queryOnly = IntegrationCapabilities.current(
                VisualEvidenceSigner.unavailable().descriptor(),
                IntegrationIdentityResolver.unavailable().descriptor(), false, null, true,
                EvidenceKeySetTrustStore.unavailable().descriptor(),
                WorkerQuarantineRequestIndexMode.KEYED_ONLY,
                WorkerQuarantineChangeAuthorizationTrustStore.unavailable().descriptor(),
                false);
        IntegrationCapabilities executable = IntegrationCapabilities.current(
                VisualEvidenceSigner.unavailable().descriptor(),
                IntegrationIdentityResolver.unavailable().descriptor(), false, null, true,
                EvidenceKeySetTrustStore.unavailable().descriptor(),
                WorkerQuarantineRequestIndexMode.KEYED_ONLY,
                WorkerQuarantineChangeAuthorizationTrustStore.unavailable().descriptor(),
                true);

        assertThat(queryOnly.testability().suiteStabilityJobSubmissionEnabled()).isFalse();
        assertThat(queryOnly.features())
                .containsEntry("asyncSuiteStabilityJobProtocol", true)
                .containsEntry("asyncSuiteStabilityJobSubmission", false)
                .containsEntry("dynamicSuiteStabilityAuthorityTrust", false)
                .containsEntry("suiteStabilityAuthorityTrustRefreshSlo", false)
                .containsEntry("asyncSuiteStabilityJobQuery", true)
                .containsEntry("asyncSuiteStabilityJobCancellation", true)
                .containsEntry("asyncSuiteStabilityJobCancellationSemanticAudit", true);
        assertThat(executable.testability().suiteStabilityJobSubmissionEnabled()).isTrue();
        assertThat(executable.features())
                .containsEntry("asyncSuiteStabilityJobSubmission", true)
                .containsEntry("suiteStabilityCurrentAuthorityRevalidation", true)
                .containsEntry("signedChallengeBoundSuiteStabilityAuthority", false);
        assertThat(executable.supportedObjects())
                .containsEntry("testSuiteStabilityJobSubmitRequest",
                        java.util.List.of(TestSuiteStabilityJobSubmitRequest.SCHEMA_VERSION))
                .containsEntry("testSuiteStabilityJobCancelRequest",
                        java.util.List.of(TestSuiteStabilityJobCancelRequest.SCHEMA_VERSION))
                .containsEntry("testSuiteStabilityJobView",
                        java.util.List.of(TestSuiteStabilityJobView.SCHEMA_VERSION))
                .containsEntry("testSuiteStabilityAuthorityRequest",
                        java.util.List.of(TestSuiteStabilityAuthorityRequest.SCHEMA_VERSION))
                .containsEntry("testSuiteStabilityAuthorityResponse",
                        java.util.List.of(TestSuiteStabilityAuthorityResponse.SCHEMA_VERSION))
                .containsEntry("testSuiteStabilityJobSubmitResponse",
                        java.util.List.of(TestSuiteStabilityJobSubmitResponse.SCHEMA_VERSION));
        assertThat(executable.endpoints()).extracting(IntegrationCapabilities.Endpoint::path)
                .contains("/api/testing/suites/{suiteId}/stability-jobs",
                        "/api/testing/stability-jobs/{jobId}",
                        "/api/testing/stability-jobs/{jobId}/cancellations");
    }

    @Test
    void signedCurrentAuthorityCapabilityRequiresExactReadyDescriptor() {
        TestSuiteStabilityJobAuthorizer.Descriptor signed =
                new TestSuiteStabilityJobAuthorizer.Descriptor(
                        "", true, "HTTPS_SIGNED_PDP", "iam.example", java.util.Map.of(
                        "signedDecisions", true,
                        "challengeBound", true,
                        "privateMaterialPresent", false));
        IntegrationCapabilities capabilities = IntegrationCapabilities.current(
                VisualEvidenceSigner.unavailable().descriptor(),
                IntegrationIdentityResolver.unavailable().descriptor(), false, null, true,
                EvidenceKeySetTrustStore.unavailable().descriptor(),
                WorkerQuarantineRequestIndexMode.KEYED_ONLY,
                WorkerQuarantineChangeAuthorizationTrustStore.unavailable().descriptor(),
                true, signed);

        assertThat(capabilities.features())
                .containsEntry("suiteStabilityCurrentAuthorityRevalidation", true)
                .containsEntry("signedChallengeBoundSuiteStabilityAuthority", true)
                .containsEntry("dynamicSuiteStabilityAuthorityTrust", false)
                .containsEntry("suiteStabilityAuthorityTrustRefreshSlo", false);
        assertThat(capabilities.testability().suiteStabilityCurrentAuthority())
                .isEqualTo(signed);
        assertThat(capabilities.toString()).doesNotContain("http://");
    }

    @Test
    void dynamicAuthorityTrustCapabilityRequiresRefreshAndFailClosedSemantics() {
        TestSuiteStabilityJobAuthorizer.Descriptor dynamic =
                new TestSuiteStabilityJobAuthorizer.Descriptor(
                        "", true, "HTTPS_SIGNED_PDP", "iam.example",
                        java.util.Map.ofEntries(
                                java.util.Map.entry("signedDecisions", true),
                                java.util.Map.entry("challengeBound", true),
                                java.util.Map.entry("privateMaterialPresent", false),
                                java.util.Map.entry("trustProviderType",
                                        "DYNAMIC_JWKS_ED25519"),
                                java.util.Map.entry("trustRefreshState", "HEALTHY"),
                                java.util.Map.entry("trustRefreshIntervalSeconds", 30),
                                java.util.Map.entry("trustMaximumSnapshotAgeSeconds", 60),
                                java.util.Map.entry("trustFailClosedOnRefreshFailure", true),
                                java.util.Map.entry("trustAutomaticRefresh", true)));

        IntegrationCapabilities capabilities = IntegrationCapabilities.current(
                VisualEvidenceSigner.unavailable().descriptor(),
                IntegrationIdentityResolver.unavailable().descriptor(), false, null, true,
                EvidenceKeySetTrustStore.unavailable().descriptor(),
                WorkerQuarantineRequestIndexMode.KEYED_ONLY,
                WorkerQuarantineChangeAuthorizationTrustStore.unavailable().descriptor(),
                true, dynamic);

        assertThat(capabilities.features())
                .containsEntry("dynamicSuiteStabilityAuthorityTrust", true)
                .containsEntry("suiteStabilityAuthorityTrustRefreshSlo", true)
                .containsEntry("suiteStabilityCurrentAuthorityRevalidation", true);
        assertThat(capabilities.testability().suiteStabilityCurrentAuthority().properties())
                .doesNotContainKeys("jwksUri", "etag", "publicKey", "privateKey");
    }

    @Test
    void executionEndpointIsAdvertisedOnlyWhenProfileMarkerEnablesIt() {
        IntegrationCapabilities disabled = IntegrationCapabilities.current();
        IntegrationCapabilities enabled = IntegrationCapabilities.current(
                VisualEvidenceSigner.unavailable().descriptor(),
                IntegrationIdentityResolver.unavailable().descriptor(), false, null, true);
        var changeTrust = new WorkerQuarantineChangeAuthorizationTrustStore.Descriptor(
                "", true, "governance.example", 2, 2, 2, 1,
                java.util.Map.of("algorithm", "Ed25519"));
        IntegrationCapabilities externallyGoverned = IntegrationCapabilities.current(
                VisualEvidenceSigner.unavailable().descriptor(),
                IntegrationIdentityResolver.unavailable().descriptor(), false, null, true,
                EvidenceKeySetTrustStore.unavailable().descriptor(),
                WorkerQuarantineRequestIndexMode.DUAL_READ_KEYED_WRITE, changeTrust);

        assertThat(disabled.testability().executionEndpointEnabled()).isFalse();
        assertThat(disabled.endpoints()).noneMatch(endpoint -> endpoint.path().startsWith("/api/testing/"));
        assertThat(disabled.features()).containsEntry("dynamicAttemptOccurrenceSelectors", false);
        assertThat(disabled.features())
                .containsEntry("durableOperatorTestExecutionCreation", false);
        assertThat(disabled.features())
                .containsEntry("boundedDurableRecoverySequence", false);
        assertThat(disabled.features())
                .containsEntry("durableRecoverySequenceRetention", false);
        assertThat(disabled.features())
                .containsEntry("durableRecoverySequenceRetentionSloHealth", false);
        assertThat(disabled.features()).containsEntry("schemaBoundaryCasePlanning", false);
        assertThat(disabled.features())
                .containsEntry("seededPropertyCasePlanning", false)
                .containsEntry("propertySuiteMaterialization", false)
                .containsEntry("propertySuiteExecution", false);
        assertThat(disabled.features())
                .containsEntry("schemaBoundarySuiteMaterialization", false)
                .containsEntry("schemaAdmissionSuiteExecution", false);
        assertThat(disabled.features()).containsEntry("durableStateProjectionAntiEntropy", false);
        assertThat(disabled.features()).containsEntry("durableStateProjectionSweepLease", false);
        assertThat(disabled.features()).containsEntry("durableStateProjectionFindingQueue", false);
        assertThat(disabled.features())
                .containsEntry("durableTestWorkerQuarantineMaintenance", false)
                .containsEntry("immutableDurableWorkerQuarantineHistory", false)
                .containsEntry("twoPersonDurableWorkerQuarantineDiscard", false)
                .containsEntry("externalWorkerQuarantineChangeAuthorization", false)
                .containsEntry("immutableApprovedWorkerQuarantineDiscardHistory", false)
                .containsEntry("encryptedDurableWorkerQuarantineClaimReplay", false)
                .containsEntry("hashedDurableWorkerQuarantineActiveFence", false)
                .containsEntry("keyedDurableWorkerQuarantineRequestIndex", false)
                .containsEntry("stagedDurableWorkerQuarantineRequestIndexUpgrade", false)
                .containsEntry("signedWorkerQuarantineRequestIndexReplicaProof", false)
                .containsEntry("durableWorkerQuarantineRequestIndexLegacyReadWrite", false)
                .containsEntry("durableWorkerQuarantineRequestIndexDualReadKeyedWrite", false)
                .containsEntry("durableWorkerQuarantineRequestIndexKeyedOnly", false)
                .containsEntry("boundedDurableWorkerQuarantineMaintenanceRetention", false);
        assertThat(disabled.features())
                .containsEntry("authenticatedDurableStateProjectionOperations", false)
                .containsEntry("immutableDurableStateProjectionActionAudit", false)
                .containsEntry("boundedDurableStateProjectionFindingRetention", false)
                .containsEntry("durableStateProjectionSloHealth", false)
                .containsEntry("boundedCardinalityDurableStateProjectionMetrics", false)
                .containsEntry("testRuntimeSloHealth", false)
                .containsEntry("boundedCardinalityTestRuntimeMetrics", false)
                .containsEntry("databaseAuthoritativeTestRuntimeAdmission", false)
                .containsEntry("boundedCardinalityTestRuntimeAdmissionMetrics", false)
                .containsEntry("pureDslMutationPlanning", false)
                .containsEntry("pureDslMutationExecution", false)
                .containsEntry("mutationScoreEvidence", false)
                .containsEntry("signedSuiteStabilityAnalysis", false)
                .containsEntry("idempotentSuiteStabilityRerun", false)
                .containsEntry("exactBinomialSuiteStabilityConfidence", false)
                .containsEntry("crossReplicaSuiteStabilityExecutionLease", false)
                .containsEntry("durableSuiteStabilityParentProgress", false);
        assertThat(enabled.testability().executionEndpointEnabled()).isTrue();
        assertThat(enabled.supportedObjects()).containsKeys("testExecutionRequest", "testExecutionResponse",
                "testExecutionBatchRequest", "testExecutionBatchResponse", "fixtureBundleRegistrationRequest",
                "storedFixtureBundle", "testSuite", "testSuiteRegistrationRequest", "storedTestSuite",
                "replayPayloadCaptureRequest", "replayPayloadDescriptor", "storedReplayPayload",
                "testSuiteExecutionRequest", "testMutationSuiteExecutionRequest",
                "testSuiteStabilityExecutionRequest", "testSuiteStabilityEvidence",
                "testSuiteStabilityAttestation", "testSuiteStabilityExecutionResponse",
                "testSuiteStabilityProgress",
                "testSuiteExecutionResponse", "testSuiteRunEvidence",
                "testSuiteRunAttestation", "testSuiteEvidenceBundle", "testSuiteRunReconciliation",
                "semanticCorrectnessWorkbookBundle",
                "testSuiteCatalogMaterialization",
                "fixtureBundle", "effectiveExecutionPlan", "executionServiceStateSnapshot",
                "testRunEvidence",
                "testEvidenceIntegrity",
                "testGraphTargetDescriptor", "testBoundaryCasePlan", "testPropertyCasePlan",
                "testMutationCasePlan",
                "testBoundarySuiteMaterializationRequest", "testBoundarySuiteMaterialization",
                "testOperatorExecutionRequest", "testOperatorTargetDescriptor",
                "durableTestOwnerClaimRequest", "durableTestOwnerClaimResponse",
                "durableTestWorkerAcquisitionRequest", "durableTestWorkerAcquisitionResponse",
                "durableTestExecutionView", "durableTestExecutionCreateRequest",
                "durableOperatorTestExecutionCreateRequest",
                "durableTestExecutionCreateResponse",
                "durableTestRecoveryHeartbeatRequest", "durableTestRecoveryHeartbeatResponse",
                "durableTestTerminalRecoveryRequest", "durableTestTerminalRecoveryResponse",
                "durableTestRecoveryStepRequest", "durableTestRecoveryStepResponse",
                "durableTestRecoverySequenceRequest", "durableTestRecoverySequenceResponse");
        assertThat(enabled.supportedObjects()).containsKeys(
                "durableStateProjectionFindingsResponse",
                "durableStateProjectionFindingClaimRequest",
                "durableStateProjectionFindingClaimResponse",
                "durableStateProjectionFindingResolutionRequest",
                "durableStateProjectionFindingResolutionResponse");
        assertThat(enabled.supportedObjects()).containsKeys(
                "durableWorkerQuarantinesResponse",
                "durableWorkerQuarantineHistoryResponse",
                "durableWorkerQuarantineClaimRequest",
                "durableWorkerQuarantineClaimResponse",
                "durableWorkerQuarantineResolutionRequest",
                "durableWorkerQuarantineResolutionResponse",
                "durableWorkerQuarantineDiscardApprovalRequest",
                "durableWorkerQuarantineDiscardApprovalResponse",
                "workerQuarantineChangeAuthorization",
                "workerQuarantineChangeAuthorizationScope",
                "workerQuarantineChangeAuthorizationSubject",
                "durableWorkerQuarantineChangeAuthorizationReference",
                "durableWorkerQuarantineApprovedDiscardRequest",
                "durableWorkerQuarantineApprovedDiscardResponse",
                "durableWorkerQuarantineApprovedDiscardHistoryResponse",
                "workerQuarantineRequestIndexReplicaProofRequest",
                "workerQuarantineRequestIndexReplicaProof");
        assertThat(enabled.features()).containsEntry("operatorMicroGraphExecution", true)
                .containsEntry("dynamicAttemptOccurrenceSelectors", true)
                .containsEntry("immutableTestSuiteRegistry", true)
                .containsEntry("immutableTestSuiteExecution", true)
                .containsEntry("suiteSemanticCoverageVerdict", true)
                .containsEntry("typedSemanticCoverageV2", true)
                .containsEntry("semanticCorrectnessWorkbookProjection", true)
                .containsEntry("suitePromotionEligibilityVerdict", true)
                .containsEntry("builtInGraphSuiteCatalogMaterialization", true)
                .containsEntry("suiteRunOwnerLease", true)
                .containsEntry("abandonedSuiteRunReconciliation", true)
                .containsEntry("governedTestReplayPayloadCapture", true)
                .containsEntry("testReplayBehavior", true)
                .containsEntry("durableTestExecutionCreation", true)
                .containsEntry("durableOperatorTestExecutionCreation", true)
                .containsEntry("durableTestCreationLeaseHeartbeat", true)
                .containsEntry("durableTestOwnerClaim", true)
                .containsEntry("durableTestWorkerPullAcquisition", true)
                .containsEntry("durableTestWorkerCyclicScanCursor", true)
                .containsEntry("durableTestWorkerCandidateBackoff", true)
                .containsEntry("durableTestWorkerCandidateQuarantine", true)
                .containsEntry("durableTestWorkerQuarantineMaintenance", true)
                .containsEntry("immutableDurableWorkerQuarantineHistory", true)
                .containsEntry("twoPersonDurableWorkerQuarantineDiscard", true)
                .containsEntry("externalWorkerQuarantineChangeAuthorization", false)
                .containsEntry("immutableApprovedWorkerQuarantineDiscardHistory", true)
                .containsEntry("encryptedDurableWorkerQuarantineClaimReplay", true)
                .containsEntry("hashedDurableWorkerQuarantineActiveFence", true)
                .containsEntry("keyedDurableWorkerQuarantineRequestIndex", true)
                .containsEntry("stagedDurableWorkerQuarantineRequestIndexUpgrade", true)
                .containsEntry("signedWorkerQuarantineRequestIndexReplicaProof", true)
                .containsEntry("durableWorkerQuarantineRequestIndexLegacyReadWrite", false)
                .containsEntry("durableWorkerQuarantineRequestIndexDualReadKeyedWrite", true)
                .containsEntry("durableWorkerQuarantineRequestIndexKeyedOnly", false)
                .containsEntry("boundedDurableWorkerQuarantineMaintenanceRetention", true)
                .containsEntry("immutableDurableWorkerNoWorkResult", true)
                .containsEntry("durableRecoveryDependencyReauthorization", true)
                .containsEntry("authenticatedDurableRecoveryHeartbeat", true)
                .containsEntry("automaticDurableRecoveryHeartbeat", true)
                .containsEntry("authenticatedDurableTerminalRecovery", true)
                .containsEntry("authenticatedDurableRecoveryStep", true)
                .containsEntry("boundedDurableRecoverySequence", true)
                .containsEntry("durableRecoverySequenceRetention", true)
                .containsEntry("durableRecoverySequenceRetentionSloHealth", true)
                .containsEntry("schemaBoundaryCasePlanning", true)
                .containsEntry("seededPropertyCasePlanning", true)
                .containsEntry("propertySuiteMaterialization", true)
                .containsEntry("propertySuiteExecution", true)
                .containsEntry("schemaBoundarySuiteMaterialization", true)
                .containsEntry("schemaAdmissionSuiteExecution", true)
                .containsEntry("durableStateProjectionAntiEntropy", true)
                .containsEntry("durableStateProjectionSweepLease", true)
                .containsEntry("durableStateProjectionFindingQueue", true)
                .containsEntry("authenticatedDurableStateProjectionOperations", true)
                .containsEntry("immutableDurableStateProjectionActionAudit", true)
                .containsEntry("boundedDurableStateProjectionFindingRetention", true)
                .containsEntry("durableStateProjectionSloHealth", true)
                .containsEntry("boundedCardinalityDurableStateProjectionMetrics", true)
                .containsEntry("testRuntimeSloHealth", true)
                .containsEntry("boundedCardinalityTestRuntimeMetrics", true)
                .containsEntry("databaseAuthoritativeTestRuntimeAdmission", true)
                .containsEntry("boundedCardinalityTestRuntimeAdmissionMetrics", true)
                .containsEntry("pureDslMutationPlanning", true)
                .containsEntry("pureDslMutationExecution", true)
                .containsEntry("mutationScoreEvidence", true)
                .containsEntry("mutationSuiteMaterialization", true)
                .containsEntry("signedSuiteStabilityAnalysis", false)
                .containsEntry("idempotentSuiteStabilityRerun", false)
                .containsEntry("exactBinomialSuiteStabilityConfidence", false)
                .containsEntry("crossReplicaSuiteStabilityExecutionLease", false)
                .containsEntry("durableSuiteStabilityParentProgress", false)
                .containsEntry("signedTestRunEvidence", false)
                .containsEntry("suiteSignedChildEvidenceGate", false)
                .containsEntry("signedTestSuiteRunAttestation", false)
                .containsEntry("portableTestSuiteEvidenceBundle", false)
                .containsEntry("streamingOperatorTestExecution", false)
                .containsEntry("suspendableOperatorTestExecution", false);
        assertThat(enabled.supportedObjects().get("testExecutionResponse"))
                .containsExactly(TestExecutionApiResponse.SCHEMA_VERSION_V1,
                        TestExecutionApiResponse.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("testBoundaryCasePlan"))
                .containsExactly(TestBoundaryCasePlan.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("testPropertyCasePlan"))
                .containsExactly(TestPropertyCasePlan.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("testMutationCasePlan"))
                .containsExactly(TestMutationCasePlan.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("testMutationSuiteExecutionRequest"))
                .containsExactly(TestMutationSuiteExecutionRequest.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("testSuiteStabilityExecutionRequest"))
                .containsExactly(TestSuiteStabilityExecutionRequest.SCHEMA_VERSION_V1,
                        TestSuiteStabilityExecutionRequest.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("testSuiteStabilityEvidence"))
                .containsExactly(TestSuiteStabilityEvidence.SCHEMA_VERSION_V1,
                        TestSuiteStabilityEvidence.SCHEMA_VERSION_V2,
                        TestSuiteStabilityEvidence.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("testSuiteStabilityAttestation"))
                .containsExactly(TestSuiteStabilityAttestation.SCHEMA_VERSION_V1,
                        TestSuiteStabilityAttestation.SCHEMA_VERSION_V2,
                        TestSuiteStabilityAttestation.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("testSuiteStabilityExecutionResponse"))
                .containsExactly(TestSuiteStabilityExecutionResponse.SCHEMA_VERSION_V1,
                        TestSuiteStabilityExecutionResponse.SCHEMA_VERSION_V2,
                        TestSuiteStabilityExecutionResponse.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("testSuiteStabilityProgress"))
                .containsExactly(TestSuiteStabilityProgressResponse.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("testBoundarySuiteMaterializationRequest"))
                .containsExactly(TestBoundarySuiteMaterializationRequest.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("testBoundarySuiteMaterialization"))
                .containsExactly(TestBoundarySuiteMaterializationResponse.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("testPropertySuiteMaterializationRequest"))
                .containsExactly(TestPropertySuiteMaterializationRequest.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("testPropertySuiteMaterialization"))
                .containsExactly(TestPropertySuiteMaterializationResponse.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("testMutationSuiteMaterializationRequest"))
                .containsExactly(TestMutationSuiteMaterializationRequest.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("testMutationSuiteMaterialization"))
                .containsExactly(TestMutationSuiteMaterializationResponse.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("testSuite"))
                .containsExactly(TestSuite.SCHEMA_VERSION, TestSuiteV2.SCHEMA_VERSION,
                        TestSuiteV3.SCHEMA_VERSION, TestSuiteV4.SCHEMA_VERSION,
                        TestSuiteV5.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("testSuiteExecutionResponse"))
                .containsExactly(TestSuiteExecutionResponse.SCHEMA_VERSION_V1,
                        TestSuiteExecutionResponse.SCHEMA_VERSION,
                        TestSuiteExecutionResponse.SCHEMA_VERSION_V3,
                        TestSuiteExecutionResponse.SCHEMA_VERSION_V4,
                        TestSuiteExecutionResponse.SCHEMA_VERSION_V5,
                        TestSuiteExecutionResponse.SCHEMA_VERSION_V6);
        assertThat(enabled.supportedObjects().get("testSuiteRunEvidence"))
                .containsExactly(TestSuiteRunEvidence.SCHEMA_VERSION,
                        TestSuiteRunEvidenceV2.SCHEMA_VERSION,
                        TestSuiteRunEvidenceV3.SCHEMA_VERSION,
                        TestSuiteRunEvidenceV4.SCHEMA_VERSION,
                        TestSuiteRunEvidenceV5.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("testSuiteRunAttestation"))
                .containsExactly(TestSuiteRunAttestation.SCHEMA_VERSION,
                        TestSuiteRunAttestation.SCHEMA_VERSION_V2,
                        TestSuiteRunAttestation.SCHEMA_VERSION_V3,
                        TestSuiteRunAttestation.SCHEMA_VERSION_V4,
                        TestSuiteRunAttestation.SCHEMA_VERSION_V5);
        assertThat(enabled.supportedObjects().get("testSuiteEvidenceBundle"))
                .containsExactly(TestSuiteEvidenceBundle.SCHEMA_VERSION,
                        TestSuiteEvidenceBundle.SCHEMA_VERSION_V2,
                        TestSuiteEvidenceBundle.SCHEMA_VERSION_V3,
                        TestSuiteEvidenceBundle.SCHEMA_VERSION_V4,
                        TestSuiteEvidenceBundle.SCHEMA_VERSION_V5);
        assertThat(enabled.supportedObjects().get("effectiveExecutionPlan"))
                .containsExactly(EffectiveExecutionPlan.SCHEMA_VERSION_V1,
                        EffectiveExecutionPlan.SCHEMA_VERSION_V2,
                        EffectiveExecutionPlan.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("executionServiceStateSnapshot"))
                .containsExactly(ExecutionServiceStateSnapshot.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("testRunEvidence"))
                .containsExactly(TestRunEvidence.SCHEMA_VERSION_V1,
                        TestRunEvidence.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("durableTestOwnerClaimRequest"))
                .containsExactly(DurableTestOwnerClaimRequest.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("durableTestOwnerClaimResponse"))
                .containsExactly(DurableTestOwnerClaimResponse.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("durableTestWorkerAcquisitionRequest"))
                .containsExactly(DurableTestWorkerAcquisitionRequest.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("durableTestWorkerAcquisitionResponse"))
                .containsExactly(DurableTestWorkerAcquisitionResponse.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("durableTestExecutionView"))
                .containsExactly(DurableTestExecutionQueryResponse.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("durableTestExecutionCreateRequest"))
                .containsExactly(DurableTestExecutionCreateRequest.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get(
                "durableOperatorTestExecutionCreateRequest"))
                .containsExactly(DurableOperatorTestExecutionCreateRequest.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("durableTestExecutionCreateResponse"))
                .containsExactly(DurableTestExecutionCreateResponse.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("durableTestRecoveryHeartbeatRequest"))
                .containsExactly(DurableTestRecoveryHeartbeatRequest.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("durableTestRecoveryHeartbeatResponse"))
                .containsExactly(DurableTestRecoveryHeartbeatResponse.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("durableTestTerminalRecoveryRequest"))
                .containsExactly(DurableTestTerminalRecoveryRequest.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("durableTestTerminalRecoveryResponse"))
                .containsExactly(DurableTestTerminalRecoveryResponse.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("durableTestRecoveryStepRequest"))
                .containsExactly(DurableTestRecoveryStepRequest.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("durableTestRecoveryStepResponse"))
                .containsExactly(DurableTestRecoveryStepResponse.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("durableTestRecoverySequenceRequest"))
                .containsExactly(DurableTestRecoverySequenceRequest.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("durableTestRecoverySequenceResponse"))
                .containsExactly(DurableTestRecoverySequenceResponse.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get(
                "durableStateProjectionFindingClaimRequest"))
                .containsExactly(DurableStateProjectionFindingClaimRequest.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get(
                "durableStateProjectionFindingResolutionResponse"))
                .containsExactly(DurableStateProjectionFindingResolutionResponse.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("durableWorkerQuarantineClaimRequest"))
                .containsExactly(DurableWorkerQuarantineClaimRequest.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("durableWorkerQuarantineResolutionResponse"))
                .containsExactly(DurableWorkerQuarantineResolutionResponse.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get(
                "durableWorkerQuarantineDiscardApprovalRequest"))
                .containsExactly(DurableWorkerQuarantineDiscardApprovalRequest.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get(
                "durableWorkerQuarantineApprovedDiscardResponse"))
                .containsExactly(DurableWorkerQuarantineApprovedDiscardResponse.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("workerQuarantineChangeAuthorization"))
                .containsExactly(WorkerQuarantineChangeAuthorization.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("workerQuarantineChangeAuthorizationScope"))
                .containsExactly(
                        WorkerQuarantineChangeAuthorizationBinding.ScopeMaterial.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get("workerQuarantineChangeAuthorizationSubject"))
                .containsExactly(
                        WorkerQuarantineChangeAuthorizationBinding.SubjectMaterial.SCHEMA_VERSION);
        assertThat(enabled.supportedObjects().get(
                "durableWorkerQuarantineChangeAuthorizationReference"))
                .containsExactly(
                        DurableWorkerQuarantineChangeAuthorizationReference.SCHEMA_VERSION);
        assertThat(externallyGoverned.features())
                .containsEntry("externalWorkerQuarantineChangeAuthorization", true);
        assertThat(externallyGoverned.testability()
                .workerQuarantineChangeAuthorizationTrust()).isEqualTo(changeTrust);
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.path().equals("/api/testing/executions"));
        assertThat(enabled.endpoints()).anyMatch(endpoint ->
                endpoint.path().equals("/api/testing/targets/graphs/{graphName}"));
        assertThat(enabled.endpoints()).anyMatch(endpoint ->
                endpoint.path().equals("/api/testing/targets/operators/{operatorRef}"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("POST")
                && endpoint.path().equals(
                "/api/testing/targets/graphs/{graphName}/boundary-suites"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("GET")
                && endpoint.path().equals(
                "/api/testing/targets/graphs/{graphName}/property-cases"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("GET")
                && endpoint.path().equals(
                "/api/testing/targets/graphs/{graphName}/mutation-cases"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("POST")
                && endpoint.path().equals(
                "/api/testing/targets/graphs/{graphName}/property-suites"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("POST")
                && endpoint.path().equals(
                "/api/testing/targets/graphs/{graphName}/mutation-suites"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("POST")
                && endpoint.path().equals(
                "/api/testing/suites/{suiteId}/mutation-executions"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("POST")
                && endpoint.path().equals(
                "/api/testing/suites/{suiteId}/stability-executions"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("GET")
                && endpoint.path().equals(
                "/api/testing/stability-executions/{stabilityRunId}"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("GET")
                && endpoint.path().equals(
                "/api/testing/stability-executions/{stabilityRunId}/progress"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("POST")
                && endpoint.path().equals(
                "/api/testing/targets/operators/{operatorRef}/boundary-suites"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("GET")
                && endpoint.path().equals(
                "/api/testing/targets/operators/{operatorRef}/property-cases"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("POST")
                && endpoint.path().equals(
                "/api/testing/targets/operators/{operatorRef}/property-suites"));
        assertThat(enabled.endpoints()).anyMatch(endpoint ->
                endpoint.path().equals("/api/testing/targets/operators/{operatorRef}/executions"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("POST")
                && endpoint.path().equals(
                "/api/testing/durable-executions/{runId}/owner-claims"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("POST")
                && endpoint.path().equals(
                "/api/testing/durable-executions/worker-acquisitions"));
        assertThat(enabled.features())
                .containsEntry("durableTestExecutionQuery", true);
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("GET")
                && endpoint.path().equals(
                "/api/testing/durable-executions/{runId}"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("POST")
                && endpoint.path().equals("/api/testing/durable-executions"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("POST")
                && endpoint.path().equals(
                "/api/testing/durable-executions/operators/{operatorRef}"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("POST")
                && endpoint.path().equals(
                "/api/testing/durable-executions/{runId}/heartbeats"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("POST")
                && endpoint.path().equals(
                "/api/testing/durable-executions/{runId}/terminal-recoveries"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("POST")
                && endpoint.path().equals(
                "/api/testing/durable-executions/{runId}/recovery-steps"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("POST")
                && endpoint.path().equals(
                "/api/testing/durable-executions/{runId}/recovery-sequences"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("GET")
                && endpoint.path().equals(
                "/api/testing/durable-state/projection-findings"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("POST")
                && endpoint.path().equals(
                "/api/testing/durable-state/projection-findings/claims"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("GET")
                && endpoint.path().equals(
                "/api/testing/durable-state/worker-quarantines"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("GET")
                && endpoint.path().equals(
                "/api/testing/durable-state/worker-quarantines/history"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("POST")
                && endpoint.path().equals(
                "/api/testing/durable-state/worker-quarantines/resolutions"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("POST")
                && endpoint.path().equals(
                "/api/testing/durable-state/worker-quarantines/discard-approvals"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("POST")
                && endpoint.path().equals(
                "/api/testing/durable-state/worker-quarantines/approved-discards"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("GET")
                && endpoint.path().equals(
                "/api/testing/durable-state/worker-quarantines/approved-discards/history"));
        assertThat(enabled.endpoints()).anyMatch(endpoint ->
                endpoint.method().equals("PUT") && endpoint.path().equals("/api/testing/suites/{suiteId}"));
        assertThat(enabled.endpoints()).anyMatch(endpoint ->
                endpoint.method().equals("GET") && endpoint.path().equals("/api/testing/suites/{suiteId}"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("POST")
                && endpoint.path().equals("/api/testing/suites/{suiteId}/executions"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("GET")
                && endpoint.path().equals("/api/testing/suite-executions/{suiteRunId}"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("GET")
                && endpoint.path().equals(
                "/api/testing/suite-executions/{suiteRunId}/evidence-bundle"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("GET")
                && endpoint.path().equals("/api/integration/test-suites/{suiteId}/revisions/{revision}"
                + "/semantic-correctness-workbook"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("PUT")
                && endpoint.path().equals("/api/testing/catalogs/gateway-graph-contract-v1"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("PUT")
                && endpoint.path().equals("/api/testing/replay-payloads/{replayPayloadId}"));

        IntegrationCapabilities signed = IntegrationCapabilities.current(
                new InMemoryVisualEvidenceSigner().descriptor(),
                IntegrationIdentityResolver.unavailable().descriptor(), false, null, true);
        assertThat(signed.features())
                .containsEntry("signedTestRunEvidence", true)
                .containsEntry("suiteSignedChildEvidenceGate", true)
                .containsEntry("signedTestSuiteRunAttestation", true)
                .containsEntry("portableTestSuiteEvidenceBundle", true)
                .containsEntry("durableSuiteStabilityParentProgress", true);
    }

    @Test
    void capabilityProbeDistinguishesEveryRequestIndexRolloutMode() {
        IntegrationCapabilities legacy = capabilitiesFor(
                WorkerQuarantineRequestIndexMode.LEGACY_READ_WRITE);
        IntegrationCapabilities dual = capabilitiesFor(
                WorkerQuarantineRequestIndexMode.DUAL_READ_KEYED_WRITE);
        IntegrationCapabilities keyedOnly = capabilitiesFor(
                WorkerQuarantineRequestIndexMode.KEYED_ONLY);

        assertThat(legacy.features())
                .containsEntry("durableWorkerQuarantineRequestIndexLegacyReadWrite", true)
                .containsEntry("durableWorkerQuarantineRequestIndexDualReadKeyedWrite", false)
                .containsEntry("durableWorkerQuarantineRequestIndexKeyedOnly", false);
        assertThat(dual.features())
                .containsEntry("durableWorkerQuarantineRequestIndexLegacyReadWrite", false)
                .containsEntry("durableWorkerQuarantineRequestIndexDualReadKeyedWrite", true)
                .containsEntry("durableWorkerQuarantineRequestIndexKeyedOnly", false);
        assertThat(keyedOnly.features())
                .containsEntry("durableWorkerQuarantineRequestIndexLegacyReadWrite", false)
                .containsEntry("durableWorkerQuarantineRequestIndexDualReadKeyedWrite", false)
                .containsEntry("durableWorkerQuarantineRequestIndexKeyedOnly", true);
    }

    private static IntegrationCapabilities capabilitiesFor(
            WorkerQuarantineRequestIndexMode mode) {
        return IntegrationCapabilities.current(
                VisualEvidenceSigner.unavailable().descriptor(),
                IntegrationIdentityResolver.unavailable().descriptor(), false, null, true,
                EvidenceKeySetTrustStore.unavailable().descriptor(), mode);
    }
}
