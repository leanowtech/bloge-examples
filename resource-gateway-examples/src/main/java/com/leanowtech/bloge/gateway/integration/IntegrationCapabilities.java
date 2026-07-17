package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.testing.domain.WorkerQuarantineRequestIndexMode;
import com.leanowtech.bloge.gateway.testing.api.WorkerQuarantineChangeAuthorizationTrustStore;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.ManagedEvidenceSigningProvider;
import com.leanowtech.bloge.gateway.visual.runtime.VisualPayloadGovernancePolicy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Machine-readable integration capability and compatibility probe.
 */
public record IntegrationCapabilities(
        String schemaVersion,
        String protocol,
        String protocolVersion,
        Map<String, List<String>> supportedObjects,
        Map<String, Boolean> features,
        IntegrationIdentityResolver.Descriptor identityProvider,
        VisualEvidenceSigner.Descriptor evidenceSigner,
        VisualPayloadGovernancePolicy.Descriptor payloadGovernance,
        Testability testability,
        List<Endpoint> endpoints
) {
    public static final String SCHEMA_VERSION = "toolStudio.resourceGateway.capabilities.v1";

    public IntegrationCapabilities {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        protocol = protocol == null || protocol.isBlank() ? ToolStudioResourceGatewayProtocol.NAME : protocol;
        protocolVersion = protocolVersion == null || protocolVersion.isBlank()
                ? ToolStudioResourceGatewayProtocol.VERSION : protocolVersion;
        supportedObjects = supportedObjects == null ? Map.of() : immutableLists(supportedObjects);
        features = features == null ? Map.of() : new LinkedHashMap<>(features);
        identityProvider = identityProvider == null
                ? IntegrationIdentityResolver.unavailable().descriptor()
                : identityProvider;
        evidenceSigner = evidenceSigner == null
                ? VisualEvidenceSigner.unavailable().descriptor()
                : evidenceSigner;
        payloadGovernance = payloadGovernance == null
                ? unavailablePayloadGovernance() : payloadGovernance;
        testability = testability == null ? Testability.schemaContractOnly() : testability;
        endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
    }

    public IntegrationCapabilities(String schemaVersion,
                                   String protocol,
                                   String protocolVersion,
                                   Map<String, List<String>> supportedObjects,
                                   Map<String, Boolean> features,
                                   IntegrationIdentityResolver.Descriptor identityProvider,
                                   List<Endpoint> endpoints) {
        this(schemaVersion, protocol, protocolVersion, supportedObjects, features, identityProvider,
                VisualEvidenceSigner.unavailable().descriptor(), unavailablePayloadGovernance(),
                Testability.schemaContractOnly(), endpoints);
    }

    public static IntegrationCapabilities current() {
        return current(false);
    }

    public static IntegrationCapabilities current(boolean evidenceSignature) {
        return current(evidenceSignature, IntegrationIdentityResolver.unavailable().descriptor());
    }

    public static IntegrationCapabilities current(boolean evidenceSignature,
                                                  IntegrationIdentityResolver.Descriptor identityProvider) {
        return current(evidenceSignature, identityProvider, false);
    }

    public static IntegrationCapabilities current(boolean evidenceSignature,
                                                  IntegrationIdentityResolver.Descriptor identityProvider,
                                                  boolean sideEffectReconcilerAdapters) {
        VisualEvidenceSigner.Descriptor signer = new VisualEvidenceSigner.Descriptor("", "LEGACY", "",
                evidenceSignature, evidenceSignature ? "HEALTHY" : "UNAVAILABLE", "", false, true,
                0, null, null, 0, 0, null);
        return current(signer, identityProvider, sideEffectReconcilerAdapters);
    }

    public static IntegrationCapabilities current(VisualEvidenceSigner.Descriptor evidenceSigner,
                                                  IntegrationIdentityResolver.Descriptor identityProvider,
                                                  boolean sideEffectReconcilerAdapters) {
        return current(evidenceSigner, identityProvider, sideEffectReconcilerAdapters,
                unavailablePayloadGovernance());
    }

    public static IntegrationCapabilities current(VisualEvidenceSigner.Descriptor evidenceSigner,
                                                  IntegrationIdentityResolver.Descriptor identityProvider,
                                                  boolean sideEffectReconcilerAdapters,
                                                  VisualPayloadGovernancePolicy.Descriptor payloadGovernance) {
        return current(evidenceSigner, identityProvider, sideEffectReconcilerAdapters,
                payloadGovernance, false);
    }

    /** Builds the current capability probe with profile-owned test execution availability. */
    public static IntegrationCapabilities current(VisualEvidenceSigner.Descriptor evidenceSigner,
                                                  IntegrationIdentityResolver.Descriptor identityProvider,
                                                  boolean sideEffectReconcilerAdapters,
                                                  VisualPayloadGovernancePolicy.Descriptor payloadGovernance,
                                                  boolean testExecutionEndpointEnabled) {
        return current(evidenceSigner, identityProvider, sideEffectReconcilerAdapters,
                payloadGovernance, testExecutionEndpointEnabled,
                EvidenceKeySetTrustStore.unavailable().descriptor());
    }

    /** Builds the capability probe with independent evidence-trust publication readiness. */
    public static IntegrationCapabilities current(VisualEvidenceSigner.Descriptor evidenceSigner,
                                                  IntegrationIdentityResolver.Descriptor identityProvider,
                                                  boolean sideEffectReconcilerAdapters,
                                                  VisualPayloadGovernancePolicy.Descriptor payloadGovernance,
                                                  boolean testExecutionEndpointEnabled,
                                                  EvidenceKeySetTrustStore.Descriptor evidenceTrust) {
        return current(evidenceSigner, identityProvider, sideEffectReconcilerAdapters,
                payloadGovernance, testExecutionEndpointEnabled, evidenceTrust,
                testExecutionEndpointEnabled
                        ? WorkerQuarantineRequestIndexMode.DUAL_READ_KEYED_WRITE : null);
    }

    /**
     * Builds the capability probe with the exact request-index upgrade mode of this replica.
     *
     * @param evidenceSigner evidence signing readiness
     * @param identityProvider integration identity readiness
     * @param sideEffectReconcilerAdapters whether real side-effect reconcilers are installed
     * @param payloadGovernance payload capture and replay governance descriptor
     * @param testExecutionEndpointEnabled whether the isolated testing runtime is assembled
     * @param evidenceTrust independently configured evidence trust-publication readiness
     * @param requestIndexMode exact request-index mode; required when test execution is enabled
     * @return immutable capability projection
     */
    public static IntegrationCapabilities current(VisualEvidenceSigner.Descriptor evidenceSigner,
                                                  IntegrationIdentityResolver.Descriptor identityProvider,
                                                  boolean sideEffectReconcilerAdapters,
                                                  VisualPayloadGovernancePolicy.Descriptor payloadGovernance,
                                                  boolean testExecutionEndpointEnabled,
                                                  EvidenceKeySetTrustStore.Descriptor evidenceTrust,
                                                  WorkerQuarantineRequestIndexMode requestIndexMode) {
        return current(evidenceSigner, identityProvider, sideEffectReconcilerAdapters,
                payloadGovernance, testExecutionEndpointEnabled, evidenceTrust,
                requestIndexMode,
                WorkerQuarantineChangeAuthorizationTrustStore.unavailable().descriptor());
    }

    /**
     * Builds the capability probe with exact request-index and external approval readiness.
     */
    public static IntegrationCapabilities current(VisualEvidenceSigner.Descriptor evidenceSigner,
                                                  IntegrationIdentityResolver.Descriptor identityProvider,
                                                  boolean sideEffectReconcilerAdapters,
                                                  VisualPayloadGovernancePolicy.Descriptor payloadGovernance,
                                                  boolean testExecutionEndpointEnabled,
                                                  EvidenceKeySetTrustStore.Descriptor evidenceTrust,
                                                  WorkerQuarantineRequestIndexMode requestIndexMode,
                                                  WorkerQuarantineChangeAuthorizationTrustStore
                                                          .Descriptor changeAuthorizationTrust) {
        VisualEvidenceSigner.Descriptor signer = evidenceSigner == null
                ? VisualEvidenceSigner.unavailable().descriptor() : evidenceSigner;
        EvidenceKeySetTrustStore.Descriptor trust = evidenceTrust == null
                ? EvidenceKeySetTrustStore.unavailable().descriptor() : evidenceTrust;
        WorkerQuarantineChangeAuthorizationTrustStore.Descriptor changeTrust =
                changeAuthorizationTrust == null
                        ? WorkerQuarantineChangeAuthorizationTrustStore.unavailable().descriptor()
                        : changeAuthorizationTrust;
        WorkerQuarantineRequestIndexMode effectiveRequestIndexMode =
                testExecutionEndpointEnabled
                        ? Objects.requireNonNull(requestIndexMode, "requestIndexMode") : null;
        Map<String, List<String>> objects = new LinkedHashMap<>();
        objects.put("graphDraft", List.of(GraphDraft.SCHEMA_VERSION));
        objects.put("operatorLibrary", List.of("bloge.visualOperatorLibrary.v1"));
        objects.put("graphDraftIntegrationBundle", List.of(GraphDraftIntegrationBundle.SCHEMA_VERSION));
        objects.put("graphDraftDependencyProfile", List.of(GraphDraftDependencyProfile.SCHEMA_VERSION_V1,
                GraphDraftDependencyProfile.SCHEMA_VERSION));
        objects.put("graphDraftDependencySnapshot", List.of(
                GraphDraftDependencyProfile.SnapshotManifest.SCHEMA_VERSION));
        objects.put("runEvidence", List.of(RunEvidenceBundle.SCHEMA_VERSION_V1,
                RunEvidenceBundle.SCHEMA_VERSION_V2, RunEvidenceBundle.SCHEMA_VERSION_V3,
                RunEvidenceBundle.SCHEMA_VERSION_V4, RunEvidenceBundle.SCHEMA_VERSION_V5,
                RunEvidenceBundle.SCHEMA_VERSION_V6, RunEvidenceBundle.SCHEMA_VERSION));
        objects.put("payloadReplay", List.of(PayloadReplayBundle.SCHEMA_VERSION_V1,
                PayloadReplayBundle.SCHEMA_VERSION));
        objects.put("payloadRetentionDescriptor", List.of(
                com.leanowtech.bloge.gateway.visual.runtime.VisualPayloadRetentionDescriptor.SCHEMA_VERSION));
        objects.put("payloadLifecycleEvent", List.of(
                com.leanowtech.bloge.gateway.visual.runtime.VisualPayloadLifecycleEvent.SCHEMA_VERSION));
        objects.put("payloadRetentionView", List.of(PayloadRetentionView.SCHEMA_VERSION));
        objects.put("payloadLifecycleCommand", List.of(PayloadLifecycleCommand.SCHEMA_VERSION));
        objects.put("payloadRetentionSweepResult", List.of(PayloadRetentionSweepResult.SCHEMA_VERSION));
        objects.put("replayExecutionRequest", List.of(ReplayExecutionRequest.SCHEMA_VERSION));
        objects.put("replayExecutionResult", List.of(ReplayExecutionResult.SCHEMA_VERSION));
        objects.put("evidenceVerificationKey", List.of(
                com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner.VerificationKey.SCHEMA_VERSION));
        objects.put("evidenceVerificationKeySet", List.of(
                com.leanowtech.bloge.gateway.visual.runtime.EvidenceVerificationKeySet.SCHEMA_VERSION));
        objects.put("evidenceKeySetTrustPublication", List.of(
                EvidenceKeySetTrustPublication.SCHEMA_VERSION));
        objects.put("evidenceKeySetTrustBundle", List.of(EvidenceKeySetTrustBundle.SCHEMA_VERSION));
        objects.put("evidenceTrustStoreDescriptor", List.of(
                EvidenceKeySetTrustStore.Descriptor.SCHEMA_VERSION));
        objects.put("evidenceSignerDescriptor", List.of(VisualEvidenceSigner.Descriptor.SCHEMA_VERSION));
        objects.put("managedEvidenceSigningKeys", List.of(
                ManagedEvidenceSigningProvider.KeySet.SCHEMA_VERSION_V1,
                ManagedEvidenceSigningProvider.KeySet.SCHEMA_VERSION));
        objects.put("managedEvidenceSignRequest", List.of(
                ManagedEvidenceSigningProvider.SignatureRequest.SCHEMA_VERSION));
        objects.put("managedEvidenceSignResponse", List.of(
                ManagedEvidenceSigningProvider.SignatureResult.SCHEMA_VERSION));
        objects.put("governanceGateResult", List.of(
                GovernanceGateResult.SCHEMA_VERSION_V1, GovernanceGateResult.SCHEMA_VERSION_V2,
                GovernanceGateResult.SCHEMA_VERSION));
        objects.put("correctnessWorkbookBundle", List.of(CorrectnessWorkbookBundle.SCHEMA_VERSION));
        objects.put("integrationEvent", List.of(IntegrationChangeEvent.SCHEMA_VERSION));
        objects.put("eventCursor", List.of(IntegrationEventCursorCodec.SCHEMA_VERSION));
        objects.put("changeFeed", List.of(IntegrationChangeFeed.SCHEMA_VERSION));
        objects.put("reconciliationSnapshot", List.of(IntegrationReconciliationSnapshot.SCHEMA_VERSION));
        objects.put("contractTestSuite", List.of(
                com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestSuite.SCHEMA_VERSION));
        objects.put("visualRunIntent", List.of(
                com.leanowtech.bloge.gateway.visual.runtime.VisualRunIntent.SCHEMA_VERSION));
        objects.put("visualRunControl", List.of(
                com.leanowtech.bloge.gateway.visual.runtime.VisualRunControlView.SCHEMA_VERSION));
        objects.put("sideEffectReconciliationRequest", List.of(SideEffectReconciliationRequest.SCHEMA_VERSION));
        objects.put("sideEffectReconciliationRecord", List.of(SideEffectReconciliationRecord.SCHEMA_VERSION));
        objects.put("sideEffectReconciliationSummary", List.of(SideEffectReconciliationSummary.SCHEMA_VERSION));
        objects.put("sideEffectProtocol", List.of(
                com.leanowtech.bloge.core.operator.SideEffectProtocol.SCHEMA_VERSION));
        objects.put("externalWriteContract", List.of(
                com.leanowtech.bloge.gateway.resource.ResourceDescriptor.ExternalWriteContract.SCHEMA_VERSION));
        if (testExecutionEndpointEnabled) {
            objects.put("testExecutionRequest", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest.SCHEMA_VERSION));
            objects.put("testExecutionResponse", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestExecutionApiResponse.SCHEMA_VERSION_V1,
                    com.leanowtech.bloge.gateway.testing.api.TestExecutionApiResponse.SCHEMA_VERSION));
            objects.put("testExecutionBatchRequest", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestExecutionBatchRequest.SCHEMA_VERSION));
            objects.put("testExecutionBatchResponse", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestExecutionBatchResponse.SCHEMA_VERSION));
            objects.put("fixtureBundleRegistrationRequest", List.of(
                    com.leanowtech.bloge.gateway.testing.api.FixtureBundleRegistrationRequest.SCHEMA_VERSION));
            objects.put("storedFixtureBundle", List.of(
                    com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundle.SCHEMA_VERSION));
            objects.put("replayPayloadCaptureRequest", List.of(
                    com.leanowtech.bloge.gateway.testing.api.ReplayPayloadCaptureRequest.SCHEMA_VERSION));
            objects.put("replayPayloadDescriptor", List.of(
                    com.leanowtech.bloge.gateway.testing.api.ReplayPayloadDescriptor.SCHEMA_VERSION));
            objects.put("storedReplayPayload", List.of(
                    com.leanowtech.bloge.gateway.testing.api.StoredReplayPayload.SCHEMA_VERSION));
            objects.put("testSuite", List.of(
                    com.leanowtech.bloge.gateway.testing.domain.TestSuite.SCHEMA_VERSION,
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteV2.SCHEMA_VERSION,
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteV3.SCHEMA_VERSION));
            objects.put("testSuiteRegistrationRequest", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestSuiteRegistrationRequest.SCHEMA_VERSION));
            objects.put("storedTestSuite", List.of(
                    com.leanowtech.bloge.gateway.testing.api.StoredTestSuite.SCHEMA_VERSION));
            objects.put("testSuiteExecutionRequest", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest.SCHEMA_VERSION));
            objects.put("testSuiteExecutionResponse", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionResponse.SCHEMA_VERSION_V1,
                    com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionResponse.SCHEMA_VERSION,
                    com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionResponse.SCHEMA_VERSION_V3,
                    com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionResponse.SCHEMA_VERSION_V4));
            objects.put("testSuiteRunEvidence", List.of(
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence.SCHEMA_VERSION,
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV2.SCHEMA_VERSION,
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV3.SCHEMA_VERSION));
            objects.put("testSuiteRunAttestation", List.of(
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation.SCHEMA_VERSION,
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation.SCHEMA_VERSION_V2,
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation.SCHEMA_VERSION_V3));
            objects.put("testSuiteEvidenceBundle", List.of(
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteEvidenceBundle.SCHEMA_VERSION,
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteEvidenceBundle.SCHEMA_VERSION_V2,
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteEvidenceBundle.SCHEMA_VERSION_V3));
            objects.put("semanticCorrectnessWorkbookBundle", List.of(
                    SemanticCorrectnessWorkbookBundle.SCHEMA_VERSION));
            objects.put("testSuiteRunReconciliation", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestSuiteRunReconciliationResult.SCHEMA_VERSION));
            objects.put("testSuiteCatalogMaterialization", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestSuiteCatalogMaterializationResponse.SCHEMA_VERSION));
            objects.put("fixtureBundle", List.of(
                    com.leanowtech.bloge.gateway.testing.domain.FixtureBundle.SCHEMA_VERSION));
            objects.put("effectiveExecutionPlan", List.of(
                    com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan.SCHEMA_VERSION_V1,
                    com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan.SCHEMA_VERSION_V2,
                    com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan.SCHEMA_VERSION));
            objects.put("executionServiceStateSnapshot", List.of(
                    com.leanowtech.bloge.gateway.testing.domain.ExecutionServiceStateSnapshot.SCHEMA_VERSION));
            objects.put("testRunEvidence", List.of(
                    com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence.SCHEMA_VERSION_V1,
                    com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence.SCHEMA_VERSION));
            objects.put("testEvidenceIntegrity", List.of(
                    com.leanowtech.bloge.gateway.testing.domain.TestEvidenceIntegrity.SCHEMA_VERSION));
            objects.put("testGraphTargetDescriptor", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestGraphTargetDescriptor.SCHEMA_VERSION));
            objects.put("testBoundaryCasePlan", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestBoundaryCasePlan.SCHEMA_VERSION));
            objects.put("testPropertyCasePlan", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestPropertyCasePlan.SCHEMA_VERSION));
            objects.put("testBoundarySuiteMaterializationRequest", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestBoundarySuiteMaterializationRequest.SCHEMA_VERSION));
            objects.put("testBoundarySuiteMaterialization", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestBoundarySuiteMaterializationResponse.SCHEMA_VERSION));
            objects.put("testOperatorExecutionRequest", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestOperatorExecutionApiRequest.SCHEMA_VERSION));
            objects.put("testOperatorTargetDescriptor", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestOperatorTargetDescriptor.SCHEMA_VERSION));
            objects.put("durableTestOwnerClaimRequest", List.of(
                    com.leanowtech.bloge.gateway.testing.api.DurableTestOwnerClaimRequest.SCHEMA_VERSION));
            objects.put("durableTestOwnerClaimResponse", List.of(
                    com.leanowtech.bloge.gateway.testing.api.DurableTestOwnerClaimResponse.SCHEMA_VERSION));
            objects.put("durableTestWorkerAcquisitionRequest", List.of(
                    com.leanowtech.bloge.gateway.testing.api.DurableTestWorkerAcquisitionRequest.SCHEMA_VERSION));
            objects.put("durableTestWorkerAcquisitionResponse", List.of(
                    com.leanowtech.bloge.gateway.testing.api.DurableTestWorkerAcquisitionResponse.SCHEMA_VERSION));
            objects.put("durableTestExecutionView", List.of(
                    com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionQueryResponse.SCHEMA_VERSION));
            objects.put("durableTestExecutionCreateRequest", List.of(
                    com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCreateRequest.SCHEMA_VERSION));
            objects.put("durableOperatorTestExecutionCreateRequest", List.of(
                    com.leanowtech.bloge.gateway.testing.api.DurableOperatorTestExecutionCreateRequest.SCHEMA_VERSION));
            objects.put("durableTestExecutionCreateResponse", List.of(
                    com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCreateResponse.SCHEMA_VERSION));
            objects.put("durableTestRecoveryHeartbeatRequest", List.of(
                    com.leanowtech.bloge.gateway.testing.api.DurableTestRecoveryHeartbeatRequest.SCHEMA_VERSION));
            objects.put("durableTestRecoveryHeartbeatResponse", List.of(
                    com.leanowtech.bloge.gateway.testing.api.DurableTestRecoveryHeartbeatResponse.SCHEMA_VERSION));
            objects.put("durableTestTerminalRecoveryRequest", List.of(
                    com.leanowtech.bloge.gateway.testing.api.DurableTestTerminalRecoveryRequest.SCHEMA_VERSION));
            objects.put("durableTestTerminalRecoveryResponse", List.of(
                    com.leanowtech.bloge.gateway.testing.api.DurableTestTerminalRecoveryResponse.SCHEMA_VERSION));
            objects.put("durableTestRecoveryStepRequest", List.of(
                    com.leanowtech.bloge.gateway.testing.api.DurableTestRecoveryStepRequest.SCHEMA_VERSION));
            objects.put("durableTestRecoveryStepResponse", List.of(
                    com.leanowtech.bloge.gateway.testing.api.DurableTestRecoveryStepResponse.SCHEMA_VERSION));
            objects.put("durableTestRecoverySequenceRequest", List.of(
                    com.leanowtech.bloge.gateway.testing.api.DurableTestRecoverySequenceRequest.SCHEMA_VERSION));
            objects.put("durableTestRecoverySequenceResponse", List.of(
                    com.leanowtech.bloge.gateway.testing.api.DurableTestRecoverySequenceResponse.SCHEMA_VERSION));
            objects.put("durableStateProjectionFindingsResponse", List.of(
                    com.leanowtech.bloge.gateway.testing.api.DurableStateProjectionFindingsResponse.SCHEMA_VERSION));
            objects.put("durableStateProjectionFindingClaimRequest", List.of(
                    com.leanowtech.bloge.gateway.testing.api.DurableStateProjectionFindingClaimRequest.SCHEMA_VERSION));
            objects.put("durableStateProjectionFindingClaimResponse", List.of(
                    com.leanowtech.bloge.gateway.testing.api.DurableStateProjectionFindingClaimResponse.SCHEMA_VERSION));
            objects.put("durableStateProjectionFindingResolutionRequest", List.of(
                    com.leanowtech.bloge.gateway.testing.api.DurableStateProjectionFindingResolutionRequest.SCHEMA_VERSION));
            objects.put("durableStateProjectionFindingResolutionResponse", List.of(
                    com.leanowtech.bloge.gateway.testing.api.DurableStateProjectionFindingResolutionResponse.SCHEMA_VERSION));
            objects.put("durableWorkerQuarantinesResponse", List.of(
                    com.leanowtech.bloge.gateway.testing.api.DurableWorkerQuarantinesResponse.SCHEMA_VERSION));
            objects.put("durableWorkerQuarantineHistoryResponse", List.of(
                    com.leanowtech.bloge.gateway.testing.api.DurableWorkerQuarantineHistoryResponse.SCHEMA_VERSION));
            objects.put("durableWorkerQuarantineClaimRequest", List.of(
                    com.leanowtech.bloge.gateway.testing.api.DurableWorkerQuarantineClaimRequest.SCHEMA_VERSION));
            objects.put("durableWorkerQuarantineClaimResponse", List.of(
                    com.leanowtech.bloge.gateway.testing.api.DurableWorkerQuarantineClaimResponse.SCHEMA_VERSION));
            objects.put("durableWorkerQuarantineResolutionRequest", List.of(
                    com.leanowtech.bloge.gateway.testing.api.DurableWorkerQuarantineResolutionRequest.SCHEMA_VERSION));
            objects.put("durableWorkerQuarantineResolutionResponse", List.of(
                    com.leanowtech.bloge.gateway.testing.api.DurableWorkerQuarantineResolutionResponse.SCHEMA_VERSION));
            objects.put("durableWorkerQuarantineDiscardApprovalRequest", List.of(
                    com.leanowtech.bloge.gateway.testing.api.DurableWorkerQuarantineDiscardApprovalRequest.SCHEMA_VERSION));
            objects.put("durableWorkerQuarantineDiscardApprovalResponse", List.of(
                    com.leanowtech.bloge.gateway.testing.api.DurableWorkerQuarantineDiscardApprovalResponse.SCHEMA_VERSION));
            objects.put("workerQuarantineChangeAuthorization", List.of(
                    com.leanowtech.bloge.gateway.testing.api
                            .WorkerQuarantineChangeAuthorization.SCHEMA_VERSION));
            objects.put("workerQuarantineChangeAuthorizationScope", List.of(
                    com.leanowtech.bloge.gateway.testing.api
                            .WorkerQuarantineChangeAuthorizationBinding.ScopeMaterial.SCHEMA_VERSION));
            objects.put("workerQuarantineChangeAuthorizationSubject", List.of(
                    com.leanowtech.bloge.gateway.testing.api
                            .WorkerQuarantineChangeAuthorizationBinding.SubjectMaterial.SCHEMA_VERSION));
            objects.put("durableWorkerQuarantineChangeAuthorizationReference", List.of(
                    com.leanowtech.bloge.gateway.testing.api
                            .DurableWorkerQuarantineChangeAuthorizationReference.SCHEMA_VERSION));
            objects.put("durableWorkerQuarantineApprovedDiscardRequest", List.of(
                    com.leanowtech.bloge.gateway.testing.api.DurableWorkerQuarantineApprovedDiscardRequest.SCHEMA_VERSION));
            objects.put("durableWorkerQuarantineApprovedDiscardResponse", List.of(
                    com.leanowtech.bloge.gateway.testing.api.DurableWorkerQuarantineApprovedDiscardResponse.SCHEMA_VERSION));
            objects.put("durableWorkerQuarantineApprovedDiscardHistoryResponse", List.of(
                    com.leanowtech.bloge.gateway.testing.api.DurableWorkerQuarantineApprovedDiscardHistoryResponse.SCHEMA_VERSION));
            objects.put("workerQuarantineRequestIndexReplicaProofRequest", List.of(
                    com.leanowtech.bloge.gateway.testing.api.WorkerQuarantineRequestIndexReplicaProofRequest.SCHEMA_VERSION));
            objects.put("workerQuarantineRequestIndexReplicaProof", List.of(
                    com.leanowtech.bloge.gateway.testing.api.WorkerQuarantineRequestIndexReplicaProof.SCHEMA_VERSION));
        }

        Map<String, Boolean> features = new LinkedHashMap<>();
        features.put("draftExportDependencyProfile", true);
        features.put("graphDraftConsistentDependencySnapshot", true);
        features.put("graphDraftStructuredDependencyRefs", true);
        features.put("runEvidenceBundle", true);
        features.put("structuredExecutionFacts", true);
        features.put("graphDeadline", true);
        features.put("operatorContextDeadlineBudget", true);
        features.put("deadlineAdmissionControl", true);
        features.put("retryBudgetEnforcement", true);
        features.put("httpRemainingBudget", true);
        features.put("remoteWorkerDeadlineBudget", true);
        features.put("userRunCancellation", true);
        features.put("runTerminationConfirmation", true);
        features.put("hardRunTermination", false);
        features.put("durableRunControl", true);
        features.put("crossInstanceRunCancellation", true);
        features.put("runOwnerLease", true);
        features.put("runOwnerEpochFencing", true);
        features.put("restartRunResumption", false);
        features.put("expiredOwnerQuarantine", true);
        features.put("runControlEvidence", true);
        features.put("runEvidenceRecoveryReservation", true);
        features.put("abandonedRunEvidenceRecovery", true);
        features.put("recoveryTransactionalOutbox", true);
        features.put("sideEffectJournal", true);
        features.put("sideEffectCommitReceipts", true);
        features.put("sideEffectReconciliation", true);
        features.put("sideEffectReconciliationEvidence", true);
        features.put("sideEffectConformanceContract", true);
        features.put("sideEffectWriteAdmission", true);
        features.put("sideEffectBindingConformance", true);
        features.put("httpWriteSideEffectProtocol", true);
        features.put("sideEffectReconcilerAdapters", sideEffectReconcilerAdapters);
        features.put("sideEffectCommitConfirmation", false);
        features.put("payloadReplay", true);
        features.put("payloadReplayNodeInputs", true);
        features.put("recordedAssertionReplay", true);
        features.put("replayExternalSideEffects", false);
        features.put("detachedPayloadVault", payloadGovernance != null && payloadGovernance.selectiveRetention());
        features.put("payloadClassificationPolicy", payloadGovernance != null && payloadGovernance.failClosed());
        features.put("selectivePayloadRetention", payloadGovernance != null
                && payloadGovernance.selectiveRetention());
        features.put("payloadLegalHold", payloadGovernance != null && payloadGovernance.legalHold());
        features.put("signedPayloadLifecycle", payloadGovernance != null && payloadGovernance.signedLifecycle());
        features.put("evidenceIntegrityManifest", true);
        features.put("evidenceSignature", signer.available());
        features.put("managedEvidenceSigning", signer.managedKeyCustody());
        features.put("nonExportableEvidenceSigningKey", signer.managedKeyCustody()
                && !signer.privateKeyExportable());
        features.put("evidenceSigningKeyRotation", signer.managedKeyCustody());
        features.put("evidenceSigningKeyRevocation", signer.managedKeyCustody());
        features.put("evidenceVerificationKeySet", signer.available()
                && Boolean.TRUE.equals(signer.properties().get("keySetPolicyAvailable")));
        features.put("timeAwareEvidenceKeyRevocation", signer.available()
                && "COMPLETE".equals(signer.properties().get("keySetPolicyCompleteness")));
        features.put("trustedEvidenceKeySetPinDistribution", trust.available());
        features.put("evidenceKeySetTransparencyLog", trust.available());
        features.put("evidenceTrustAuthorityQuorum", trust.available()
                && trust.signatureThreshold() > 0);
        features.put("evidenceTrustRollbackAndForkDetection", trust.available());
        features.put("evidenceSigningFailClosed", signer.managedKeyCustody()
                && Boolean.TRUE.equals(signer.properties().get("failClosedAfterSnapshotExpiry")));
        features.put("deepLinks", true);
        features.put("governanceGateFeedback", true);
        features.put("correctnessWorkbookProjection", true);
        features.put("workbookEvidenceReferences", true);
        features.put("transactionalOutbox", true);
        features.put("eventCursor", true);
        features.put("reconciliationSnapshot", true);
        features.put("trustedWorkloadIdentity", identityProvider != null && identityProvider.available());
        features.put("demoIdentityMode", identityProvider != null && identityProvider.demoMode());
        features.put("signedWorkloadJwt", identityProvider != null
                && "SIGNED_JWT".equals(identityProvider.providerType()));
        features.put("credentialRotation", identityProvider != null
                && Boolean.TRUE.equals(identityProvider.properties().get("keyRotationSupported")));
        features.put("credentialRevocation", identityProvider != null
                && Boolean.TRUE.equals(identityProvider.properties().get("keyRevocationSupported")));
        features.put("dynamicCredentialTrust", identityProvider != null
                && Boolean.TRUE.equals(identityProvider.properties().get("dynamicRefreshSupported")));
        features.put("credentialRevocationPropagationSlo", identityProvider != null
                && identityProvider.properties().get("revocationPropagationSloSeconds") instanceof Number);
        features.put("webhook", false);
        features.put("operatorMicroGraphExecution", testExecutionEndpointEnabled);
        features.put("schemaBoundaryCasePlanning", testExecutionEndpointEnabled);
        features.put("seededPropertyCasePlanning", testExecutionEndpointEnabled);
        features.put("propertySuiteExecution", false);
        features.put("schemaBoundarySuiteMaterialization", testExecutionEndpointEnabled);
        features.put("schemaAdmissionSuiteExecution", testExecutionEndpointEnabled);
        features.put("dynamicAttemptOccurrenceSelectors", testExecutionEndpointEnabled);
        features.put("immutableTestSuiteRegistry", testExecutionEndpointEnabled);
        features.put("immutableTestSuiteExecution", testExecutionEndpointEnabled);
        features.put("suiteSemanticCoverageVerdict", testExecutionEndpointEnabled);
        features.put("typedSemanticCoverageV2", testExecutionEndpointEnabled);
        features.put("semanticCorrectnessWorkbookProjection", testExecutionEndpointEnabled);
        features.put("suitePromotionEligibilityVerdict", testExecutionEndpointEnabled);
        features.put("builtInGraphSuiteCatalogMaterialization", testExecutionEndpointEnabled);
        features.put("suiteRunOwnerLease", testExecutionEndpointEnabled);
        features.put("abandonedSuiteRunReconciliation", testExecutionEndpointEnabled);
        features.put("governedTestReplayPayloadCapture", testExecutionEndpointEnabled);
        features.put("testReplayBehavior", testExecutionEndpointEnabled);
        features.put("durableTestExecutionQuery", testExecutionEndpointEnabled);
        features.put("durableTestExecutionCreation", testExecutionEndpointEnabled);
        features.put("durableOperatorTestExecutionCreation", testExecutionEndpointEnabled);
        features.put("durableTestCreationLeaseHeartbeat", testExecutionEndpointEnabled);
        features.put("durableTestOwnerClaim", testExecutionEndpointEnabled);
        features.put("durableTestWorkerPullAcquisition", testExecutionEndpointEnabled);
        features.put("durableTestWorkerCyclicScanCursor", testExecutionEndpointEnabled);
        features.put("durableTestWorkerCandidateBackoff", testExecutionEndpointEnabled);
        features.put("durableTestWorkerCandidateQuarantine", testExecutionEndpointEnabled);
        features.put("durableTestWorkerQuarantineMaintenance", testExecutionEndpointEnabled);
        features.put("immutableDurableWorkerQuarantineHistory", testExecutionEndpointEnabled);
        features.put("twoPersonDurableWorkerQuarantineDiscard", testExecutionEndpointEnabled);
        features.put("externalWorkerQuarantineChangeAuthorization",
                testExecutionEndpointEnabled && changeTrust.available());
        features.put("immutableApprovedWorkerQuarantineDiscardHistory",
                testExecutionEndpointEnabled);
        features.put("encryptedDurableWorkerQuarantineClaimReplay",
                testExecutionEndpointEnabled);
        features.put("hashedDurableWorkerQuarantineActiveFence",
                testExecutionEndpointEnabled);
        features.put("keyedDurableWorkerQuarantineRequestIndex",
                testExecutionEndpointEnabled);
        features.put("stagedDurableWorkerQuarantineRequestIndexUpgrade",
                testExecutionEndpointEnabled);
        features.put("signedWorkerQuarantineRequestIndexReplicaProof",
                testExecutionEndpointEnabled);
        features.put("durableWorkerQuarantineRequestIndexLegacyReadWrite",
                effectiveRequestIndexMode
                        == WorkerQuarantineRequestIndexMode.LEGACY_READ_WRITE);
        features.put("durableWorkerQuarantineRequestIndexDualReadKeyedWrite",
                effectiveRequestIndexMode
                        == WorkerQuarantineRequestIndexMode.DUAL_READ_KEYED_WRITE);
        features.put("durableWorkerQuarantineRequestIndexKeyedOnly",
                effectiveRequestIndexMode == WorkerQuarantineRequestIndexMode.KEYED_ONLY);
        features.put("boundedDurableWorkerQuarantineMaintenanceRetention",
                testExecutionEndpointEnabled);
        features.put("immutableDurableWorkerNoWorkResult", testExecutionEndpointEnabled);
        features.put("durableRecoveryDependencyReauthorization", testExecutionEndpointEnabled);
        features.put("authenticatedDurableRecoveryHeartbeat", testExecutionEndpointEnabled);
        features.put("automaticDurableRecoveryHeartbeat", testExecutionEndpointEnabled);
        features.put("authenticatedDurableTerminalRecovery", testExecutionEndpointEnabled);
        features.put("authenticatedDurableRecoveryStep", testExecutionEndpointEnabled);
        features.put("boundedDurableRecoverySequence", testExecutionEndpointEnabled);
        features.put("durableRecoverySequenceRetention", testExecutionEndpointEnabled);
        features.put("durableRecoverySequenceRetentionSloHealth",
                testExecutionEndpointEnabled);
        features.put("durableStateProjectionAntiEntropy", testExecutionEndpointEnabled);
        features.put("durableStateProjectionSweepLease", testExecutionEndpointEnabled);
        features.put("durableStateProjectionFindingQueue", testExecutionEndpointEnabled);
        features.put("authenticatedDurableStateProjectionOperations",
                testExecutionEndpointEnabled);
        features.put("immutableDurableStateProjectionActionAudit",
                testExecutionEndpointEnabled);
        features.put("boundedDurableStateProjectionFindingRetention",
                testExecutionEndpointEnabled);
        features.put("durableStateProjectionSloHealth", testExecutionEndpointEnabled);
        features.put("boundedCardinalityDurableStateProjectionMetrics",
                testExecutionEndpointEnabled);
        features.put("testRuntimeSloHealth", testExecutionEndpointEnabled);
        features.put("boundedCardinalityTestRuntimeMetrics",
                testExecutionEndpointEnabled);
        features.put("databaseAuthoritativeTestRuntimeAdmission",
                testExecutionEndpointEnabled);
        features.put("boundedCardinalityTestRuntimeAdmissionMetrics",
                testExecutionEndpointEnabled);
        features.put("signedTestRunEvidence", testExecutionEndpointEnabled && signer.available());
        features.put("suiteSignedChildEvidenceGate", testExecutionEndpointEnabled && signer.available());
        features.put("signedTestSuiteRunAttestation",
                testExecutionEndpointEnabled && signer.available());
        features.put("portableTestSuiteEvidenceBundle",
                testExecutionEndpointEnabled && signer.available());
        features.put("streamingOperatorTestExecution", false);
        features.put("suspendableOperatorTestExecution", false);

        List<Endpoint> endpoints = new java.util.ArrayList<>(List.of(
                new Endpoint("GET", "/api/integration/capabilities"),
                new Endpoint("GET", "/api/integration/drafts/{draftId}/export"),
                new Endpoint("GET", "/api/integration/drafts/{draftId}/correctness-workbook"),
                new Endpoint("GET", "/api/integration/runs/{runId}/evidence"),
                new Endpoint("GET", "/api/integration/runs/{runId}/side-effects/reconciliations"),
                new Endpoint("POST", "/api/integration/runs/{runId}/side-effects/{attemptId}/reconcile"),
                new Endpoint("GET", "/api/integration/runs/{runId}/replay"),
                new Endpoint("POST", "/api/integration/runs/{runId}/replay"),
                new Endpoint("GET", "/api/integration/runs/{runId}/payload-retention"),
                new Endpoint("POST", "/api/integration/runs/{runId}/payload-retention/holds"),
                new Endpoint("POST", "/api/integration/runs/{runId}/payload-retention/holds/{holdId}/release"),
                new Endpoint("POST", "/api/integration/runs/{runId}/payload-retention/purge"),
                new Endpoint("POST", "/api/integration/payload-retention/purge-expired"),
                new Endpoint("GET", "/api/integration/evidence-keys/{keyId}"),
                new Endpoint("GET", "/api/integration/evidence-keys"),
                new Endpoint("POST", "/api/integration/evidence-keys/trust-publications"),
                new Endpoint("GET", "/api/integration/evidence-keys/trust-bundle"),
                new Endpoint("POST", "/api/integration/gate-results"),
                new Endpoint("GET", "/api/integration/drafts/{draftId}/gate-result"),
                new Endpoint("GET", "/api/integration/events"),
                new Endpoint("GET", "/api/integration/reconciliation"),
                new Endpoint("GET", "/api/integration/operator-libraries/{libraryId}"),
                new Endpoint("GET", "/api/integration/operator-test-suites/{suiteId}"),
                new Endpoint("GET", "/api/visual/run-controls/{requestId}"),
                new Endpoint("POST", "/api/visual/run-controls/{requestId}/cancel")
        ));
        if (testExecutionEndpointEnabled) {
            endpoints.add(new Endpoint("POST", "/api/testing/executions"));
            endpoints.add(new Endpoint("GET", "/api/testing/targets/graphs/{graphName}"));
            endpoints.add(new Endpoint("GET",
                    "/api/testing/targets/graphs/{graphName}/boundary-cases"));
            endpoints.add(new Endpoint("GET",
                    "/api/testing/targets/graphs/{graphName}/property-cases"));
            endpoints.add(new Endpoint("POST",
                    "/api/testing/targets/graphs/{graphName}/boundary-suites"));
            endpoints.add(new Endpoint("GET", "/api/testing/targets/operators/{operatorRef}"));
            endpoints.add(new Endpoint("GET",
                    "/api/testing/targets/operators/{operatorRef}/boundary-cases"));
            endpoints.add(new Endpoint("GET",
                    "/api/testing/targets/operators/{operatorRef}/property-cases"));
            endpoints.add(new Endpoint("POST",
                    "/api/testing/targets/operators/{operatorRef}/boundary-suites"));
            endpoints.add(new Endpoint("POST", "/api/testing/targets/operators/{operatorRef}/executions"));
            endpoints.add(new Endpoint("POST", "/api/testing/executions/batch"));
            endpoints.add(new Endpoint("GET", "/api/testing/executions/{runId}"));
            endpoints.add(new Endpoint("GET",
                    "/api/testing/durable-executions/{runId}"));
            endpoints.add(new Endpoint("POST",
                    "/api/testing/durable-executions"));
            endpoints.add(new Endpoint("POST",
                    "/api/testing/durable-executions/operators/{operatorRef}"));
            endpoints.add(new Endpoint("POST",
                    "/api/testing/durable-executions/{runId}/owner-claims"));
            endpoints.add(new Endpoint("POST",
                    "/api/testing/durable-executions/worker-acquisitions"));
            endpoints.add(new Endpoint("POST",
                    "/api/testing/durable-executions/{runId}/heartbeats"));
            endpoints.add(new Endpoint("POST",
                    "/api/testing/durable-executions/{runId}/terminal-recoveries"));
            endpoints.add(new Endpoint("POST",
                    "/api/testing/durable-executions/{runId}/recovery-steps"));
            endpoints.add(new Endpoint("POST",
                    "/api/testing/durable-executions/{runId}/recovery-sequences"));
            endpoints.add(new Endpoint("GET",
                    "/api/testing/durable-state/projection-findings"));
            endpoints.add(new Endpoint("POST",
                    "/api/testing/durable-state/projection-findings/claims"));
            endpoints.add(new Endpoint("POST",
                    "/api/testing/durable-state/projection-findings/resolutions"));
            endpoints.add(new Endpoint("GET",
                    "/api/testing/durable-state/worker-quarantines"));
            endpoints.add(new Endpoint("GET",
                    "/api/testing/durable-state/worker-quarantines/history"));
            endpoints.add(new Endpoint("POST",
                    "/api/testing/durable-state/worker-quarantines/claims"));
            endpoints.add(new Endpoint("POST",
                    "/api/testing/durable-state/worker-quarantines/resolutions"));
            endpoints.add(new Endpoint("POST",
                    "/api/testing/durable-state/worker-quarantines/discard-approvals"));
            endpoints.add(new Endpoint("POST",
                    "/api/testing/durable-state/worker-quarantines/approved-discards"));
            endpoints.add(new Endpoint("GET",
                    "/api/testing/durable-state/worker-quarantines/approved-discards/history"));
            endpoints.add(new Endpoint("POST",
                    "/api/testing/durable-state/worker-quarantines/request-index/replica-proofs"));
            endpoints.add(new Endpoint("PUT", "/api/testing/fixture-bundles/{fixtureBundleId}"));
            endpoints.add(new Endpoint("GET", "/api/testing/fixture-bundles/{fixtureBundleId}"));
            endpoints.add(new Endpoint("PUT", "/api/testing/replay-payloads/{replayPayloadId}"));
            endpoints.add(new Endpoint("GET", "/api/testing/replay-payloads/{replayPayloadId}"));
            endpoints.add(new Endpoint("PUT", "/api/testing/suites/{suiteId}"));
            endpoints.add(new Endpoint("GET", "/api/testing/suites/{suiteId}"));
            endpoints.add(new Endpoint("PUT", "/api/testing/catalogs/gateway-graph-contract-v1"));
            endpoints.add(new Endpoint("POST", "/api/testing/suites/{suiteId}/executions"));
            endpoints.add(new Endpoint("GET", "/api/testing/suite-executions/{suiteRunId}"));
            endpoints.add(new Endpoint("GET",
                    "/api/testing/suite-executions/{suiteRunId}/evidence-bundle"));
            endpoints.add(new Endpoint("GET",
                    "/api/integration/test-suites/{suiteId}/revisions/{revision}"
                            + "/semantic-correctness-workbook"));
        }
        return new IntegrationCapabilities("", "", "", objects, features, identityProvider, signer,
                payloadGovernance, testExecutionEndpointEnabled
                        ? Testability.executionControlPlane(changeTrust)
                        : Testability.schemaContractOnly(),
                endpoints);
    }

    private static VisualPayloadGovernancePolicy.Descriptor unavailablePayloadGovernance() {
        return new VisualPayloadGovernancePolicy.Descriptor("", "", "", "RESTRICTED",
                false, false, false, true);
    }

    private static Map<String, List<String>> immutableLists(Map<String, List<String>> values) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(key, value == null ? List.of() : List.copyOf(value)));
        return copy;
    }

    public record Endpoint(String method, String path) {
        public Endpoint {
            method = method == null ? "" : method.trim().toUpperCase();
            path = path == null ? "" : path.trim();
        }
    }

    /**
     * Machine-readable state of the execution-control protocol.
     *
     * @param protocolVersion Resource Gateway testability protocol version
     * @param enabledEnvironments environments allowed to expose caller-driven testing endpoints
     * @param schemaContractMode whether schema-only operator checks are available
     * @param executionEndpointEnabled whether caller-driven execution is currently implemented
     * @param workerQuarantineChangeAuthorizationTrust key-free external approval readiness
     */
    public record Testability(
            String protocolVersion,
            List<String> enabledEnvironments,
            boolean schemaContractMode,
            boolean executionEndpointEnabled,
            WorkerQuarantineChangeAuthorizationTrustStore.Descriptor
                    workerQuarantineChangeAuthorizationTrust
    ) {
        /** Normalizes capability values. */
        public Testability {
            protocolVersion = protocolVersion == null || protocolVersion.isBlank()
                    ? "bloge.testing.v1" : protocolVersion.trim();
            enabledEnvironments = enabledEnvironments == null
                    ? List.of() : List.copyOf(enabledEnvironments);
            workerQuarantineChangeAuthorizationTrust =
                    workerQuarantineChangeAuthorizationTrust == null
                            ? WorkerQuarantineChangeAuthorizationTrustStore.unavailable().descriptor()
                            : workerQuarantineChangeAuthorizationTrust;
        }

        /** @return Stage 0 capability before the execution endpoint is activated */
        public static Testability schemaContractOnly() {
            return new Testability("bloge.testing.v1", List.of("test", "staging"),
                    true, false,
                    WorkerQuarantineChangeAuthorizationTrustStore.unavailable().descriptor());
        }

        /** @return Stage 2 capability with the caller-driven execution endpoint assembled */
        public static Testability executionControlPlane() {
            return executionControlPlane(
                    WorkerQuarantineChangeAuthorizationTrustStore.unavailable().descriptor());
        }

        /** @return execution capability with exact external approval trust readiness */
        public static Testability executionControlPlane(
                WorkerQuarantineChangeAuthorizationTrustStore.Descriptor trust) {
            return new Testability("bloge.testing.v1", List.of("test", "staging"),
                    true, true, trust);
        }
    }
}
