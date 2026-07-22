package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.testing.api.DynamicJwksTestSecretAuthorityTrustStore;
import com.leanowtech.bloge.gateway.testing.api.DynamicTestSecretAuthorityServingInventoryAuthority;
import com.leanowtech.bloge.gateway.testing.api.DynamicTestSecretAuthorityServingInventoryTrustRootAuthority;
import com.leanowtech.bloge.gateway.testing.api.ConfiguredTestSecretAuthorityServingInventoryTrustRootAuthority;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability;
import com.leanowtech.bloge.gateway.testing.api.TestSecretAuthority;
import com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityResponse;
import com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityServingInventory;
import com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityServingInventoryAuthority;
import com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityServingInventoryPublication;
import com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityServingInventoryPublicationFloor;
import com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityServingInventoryTrustRootFloor;
import com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityServingInventoryTrustRootPublication;
import com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityTrustCohortPolicy;
import com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityTrustCohortGate;
import com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityTrustCohortRepository;
import com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityTrustStore;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAuthorityRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAuthorityResponse;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAuthorityTrustStore;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobAuthorizer;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveReconciliationHealth;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptRuntimeCapability;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptProviderInventory;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptProviderInventoryCohortGate;
import com.leanowtech.bloge.gateway.testing.api.WorkerQuarantineChangeAuthorizationTrustStore;
import com.leanowtech.bloge.gateway.testing.domain.WorkerQuarantineRequestIndexMode;
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
        return current(evidenceSigner, identityProvider, sideEffectReconcilerAdapters,
                payloadGovernance, testExecutionEndpointEnabled, evidenceTrust,
                requestIndexMode, changeAuthorizationTrust, false);
    }

    /**
     * Builds the capability probe with exact runtime availability for asynchronous stability jobs.
     */
    public static IntegrationCapabilities current(VisualEvidenceSigner.Descriptor evidenceSigner,
                                                  IntegrationIdentityResolver.Descriptor identityProvider,
                                                  boolean sideEffectReconcilerAdapters,
                                                  VisualPayloadGovernancePolicy.Descriptor payloadGovernance,
                                                  boolean testExecutionEndpointEnabled,
                                                  EvidenceKeySetTrustStore.Descriptor evidenceTrust,
                                                  WorkerQuarantineRequestIndexMode requestIndexMode,
                                                  WorkerQuarantineChangeAuthorizationTrustStore
                                                          .Descriptor changeAuthorizationTrust,
                                                  boolean suiteStabilityJobSubmissionEnabled) {
        return current(evidenceSigner, identityProvider, sideEffectReconcilerAdapters,
                payloadGovernance, testExecutionEndpointEnabled, evidenceTrust,
                requestIndexMode, changeAuthorizationTrust,
                suiteStabilityJobSubmissionEnabled,
                suiteStabilityJobSubmissionEnabled
                        ? customCurrentAuthority() : unavailableCurrentAuthority());
    }

    /**
     * Builds the capability probe with exact asynchronous current-authority readiness.
     */
    public static IntegrationCapabilities current(VisualEvidenceSigner.Descriptor evidenceSigner,
                                                  IntegrationIdentityResolver.Descriptor identityProvider,
                                                  boolean sideEffectReconcilerAdapters,
                                                  VisualPayloadGovernancePolicy.Descriptor payloadGovernance,
                                                  boolean testExecutionEndpointEnabled,
                                                  EvidenceKeySetTrustStore.Descriptor evidenceTrust,
                                                  WorkerQuarantineRequestIndexMode requestIndexMode,
                                                  WorkerQuarantineChangeAuthorizationTrustStore
                                                          .Descriptor changeAuthorizationTrust,
                                                  boolean suiteStabilityJobSubmissionEnabled,
                                                  TestSuiteStabilityJobAuthorizer.Descriptor
                                                          currentAuthority) {
        return current(evidenceSigner, identityProvider, sideEffectReconcilerAdapters,
                payloadGovernance, testExecutionEndpointEnabled, evidenceTrust, requestIndexMode,
                changeAuthorizationTrust, suiteStabilityJobSubmissionEnabled, currentAuthority,
                TestSuiteStabilityObservationExternalArchiveReconciliationHealth.Descriptor
                        .unavailable());
    }

    /**
     * Builds the capability probe with exact external archive reconciliation readiness.
     */
    public static IntegrationCapabilities current(VisualEvidenceSigner.Descriptor evidenceSigner,
                                                  IntegrationIdentityResolver.Descriptor identityProvider,
                                                  boolean sideEffectReconcilerAdapters,
                                                  VisualPayloadGovernancePolicy.Descriptor payloadGovernance,
                                                  boolean testExecutionEndpointEnabled,
                                                  EvidenceKeySetTrustStore.Descriptor evidenceTrust,
                                                  WorkerQuarantineRequestIndexMode requestIndexMode,
                                                  WorkerQuarantineChangeAuthorizationTrustStore
                                                          .Descriptor changeAuthorizationTrust,
                                                  boolean suiteStabilityJobSubmissionEnabled,
                                                  TestSuiteStabilityJobAuthorizer.Descriptor
                                                          currentAuthority,
                                                  TestSuiteStabilityObservationExternalArchiveReconciliationHealth
                                                          .Descriptor archiveReconciliation) {
        if (suiteStabilityJobSubmissionEnabled && !testExecutionEndpointEnabled) {
            throw new IllegalArgumentException(
                    "Stability-job submission requires the testing control plane");
        }
        VisualEvidenceSigner.Descriptor signer = evidenceSigner == null
                ? VisualEvidenceSigner.unavailable().descriptor() : evidenceSigner;
        EvidenceKeySetTrustStore.Descriptor trust = evidenceTrust == null
                ? EvidenceKeySetTrustStore.unavailable().descriptor() : evidenceTrust;
        WorkerQuarantineChangeAuthorizationTrustStore.Descriptor changeTrust =
                changeAuthorizationTrust == null
                        ? WorkerQuarantineChangeAuthorizationTrustStore.unavailable().descriptor()
                        : changeAuthorizationTrust;
        TestSuiteStabilityJobAuthorizer.Descriptor authority = currentAuthority == null
                ? unavailableCurrentAuthority() : currentAuthority;
        TestSuiteStabilityObservationExternalArchiveReconciliationHealth.Descriptor
                reconciliation = archiveReconciliation == null
                ? TestSuiteStabilityObservationExternalArchiveReconciliationHealth.Descriptor
                .unavailable() : archiveReconciliation;
        if (suiteStabilityJobSubmissionEnabled && !authority.available()) {
            throw new IllegalArgumentException(
                    "Stability-job submission requires current-authority readiness");
        }
        WorkerQuarantineRequestIndexMode effectiveRequestIndexMode =
                testExecutionEndpointEnabled
                        ? Objects.requireNonNull(requestIndexMode, "requestIndexMode") : null;
        Map<String, List<String>> objects = new LinkedHashMap<>();
        objects.put("graphDraft", List.of(GraphDraft.SCHEMA_VERSION));
        objects.put("capabilitySnapshot", List.of(
                com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot.SCHEMA_VERSION));
        objects.put("capabilityClosure", List.of(
                com.leanowtech.bloge.gateway.integration.mirror.CapabilityClosure.SCHEMA_VERSION));
        objects.put("capabilityClosureProjectionRequest", List.of(
                com.leanowtech.bloge.gateway.integration.mirror
                        .CapabilityClosureProjectionRequest.SCHEMA_VERSION));
        objects.put("capabilityContract", List.of(
                com.leanowtech.bloge.gateway.integration.mirror.CapabilityContract.SCHEMA_VERSION));
        objects.put("effectContract", List.of(
                com.leanowtech.bloge.gateway.integration.mirror.EffectContract.SCHEMA_VERSION));
        objects.put("artifactProvenance", List.of(
                com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance.SCHEMA_VERSION));
        objects.put("capabilityLifecycleTransition", List.of(
                com.leanowtech.bloge.gateway.integration.mirror
                        .CapabilityLifecycleTransitionRequest.SCHEMA_VERSION));
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
        objects.put("externalArchiveReconciliationReadiness", List.of(
                TestSuiteStabilityObservationExternalArchiveReconciliationHealth.SCHEMA_VERSION_V1,
                TestSuiteStabilityObservationExternalArchiveReconciliationHealth.SCHEMA_VERSION));
        objects.put("externalSequenceAnchorBootstrapRootRecoveryFleetCapability", List.of(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.SCHEMA_VERSION_V1,
                ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.SCHEMA_VERSION_V2,
                ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.SCHEMA_VERSION_V3,
                ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.SCHEMA_VERSION));
        objects.put("physicalAttemptProviderInventory", List.of(
                TestSuiteStabilityPhysicalAttemptProviderInventory.SCHEMA_VERSION));
        objects.put("physicalAttemptProviderInventoryDescriptor", List.of(
                TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.Descriptor
                        .SCHEMA_VERSION_V1,
                TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.Descriptor
                        .SCHEMA_VERSION));
        objects.put("physicalAttemptProviderInventoryCohortObservation", List.of(
                TestSuiteStabilityPhysicalAttemptProviderInventoryCohortGate.Observation
                        .SCHEMA_VERSION));
        objects.put("physicalAttemptRuntimeCapability", List.of(
                TestSuiteStabilityPhysicalAttemptRuntimeCapability.SCHEMA_VERSION_V1,
                TestSuiteStabilityPhysicalAttemptRuntimeCapability.SCHEMA_VERSION));
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
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteV3.SCHEMA_VERSION,
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteV4.SCHEMA_VERSION,
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteV5.SCHEMA_VERSION));
            objects.put("testSuiteRegistrationRequest", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestSuiteRegistrationRequest.SCHEMA_VERSION));
            objects.put("storedTestSuite", List.of(
                    com.leanowtech.bloge.gateway.testing.api.StoredTestSuite.SCHEMA_VERSION));
            objects.put("testSuiteExecutionRequest", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest.SCHEMA_VERSION));
            objects.put("testMutationSuiteExecutionRequest", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestMutationSuiteExecutionRequest
                            .SCHEMA_VERSION));
            objects.put("testSuiteStabilityExecutionRequest", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionRequest
                            .SCHEMA_VERSION_V1,
                    com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionRequest
                            .SCHEMA_VERSION_V2,
                    com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionRequest
                            .SCHEMA_VERSION_V3,
                    com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionRequest
                            .SCHEMA_VERSION));
            objects.put("testSuiteStabilityEvidence", List.of(
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityEvidence
                            .SCHEMA_VERSION_V1,
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityEvidence
                            .SCHEMA_VERSION_V2,
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityEvidence
                            .SCHEMA_VERSION_V3,
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityEvidence
                            .SCHEMA_VERSION_V4,
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityEvidence
                            .SCHEMA_VERSION));
            objects.put("testSuiteStabilityAttestation", List.of(
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityAttestation
                            .SCHEMA_VERSION_V1,
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityAttestation
                            .SCHEMA_VERSION_V2,
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityAttestation
                            .SCHEMA_VERSION_V3,
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityAttestation
                            .SCHEMA_VERSION_V4,
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityAttestation
                            .SCHEMA_VERSION));
            objects.put("testSuiteStabilityExecutionResponse", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionResponse
                            .SCHEMA_VERSION_V1,
                    com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionResponse
                            .SCHEMA_VERSION_V2,
                    com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionResponse
                            .SCHEMA_VERSION_V3,
                    com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionResponse
                            .SCHEMA_VERSION_V4,
                    com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionResponse
                            .SCHEMA_VERSION));
            objects.put("testSuiteStabilityProgress", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityProgressResponse
                            .SCHEMA_VERSION_V1,
                    com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityProgressResponse
                            .SCHEMA_VERSION));
            objects.put("testSuiteStabilityTrendAnalysisRequest", List.of(
                    com.leanowtech.bloge.gateway.testing.api
                            .TestSuiteStabilityTrendAnalysisRequest.SCHEMA_VERSION));
            objects.put("testSuiteStabilityTrendEvidence", List.of(
                    com.leanowtech.bloge.gateway.testing.domain
                            .TestSuiteStabilityTrendEvidence.SCHEMA_VERSION));
            objects.put("testSuiteStabilityTrendAttestation", List.of(
                    com.leanowtech.bloge.gateway.testing.domain
                            .TestSuiteStabilityTrendAttestation.SCHEMA_VERSION));
            objects.put("testSuiteStabilityTrendAnalysisResponse", List.of(
                    com.leanowtech.bloge.gateway.testing.api
                            .TestSuiteStabilityTrendAnalysisResponse.SCHEMA_VERSION));
            objects.put("testSuiteStabilityJobSubmitRequest", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobSubmitRequest
                            .SCHEMA_VERSION));
            objects.put("testSuiteStabilityJobCancelRequest", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobCancelRequest
                            .SCHEMA_VERSION));
            objects.put("testSuiteStabilityJobView", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobView
                            .SCHEMA_VERSION));
            objects.put("testSuiteStabilityAuthorityRequest", List.of(
                    TestSuiteStabilityAuthorityRequest.SCHEMA_VERSION));
            objects.put("testSuiteStabilityAuthorityResponse", List.of(
                    TestSuiteStabilityAuthorityResponse.SCHEMA_VERSION));
            objects.put("testSuiteStabilityAuthorityTrustDescriptor", List.of(
                    TestSuiteStabilityAuthorityTrustStore.Descriptor.SCHEMA_VERSION));
            objects.put("testSuiteStabilityJobAuthorizerDescriptor", List.of(
                    TestSuiteStabilityJobAuthorizer.Descriptor.SCHEMA_VERSION));
            objects.put("testSuiteStabilityJobSubmitResponse", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobSubmitResponse
                            .SCHEMA_VERSION));
            objects.put("testSuiteExecutionResponse", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionResponse.SCHEMA_VERSION_V1,
                    com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionResponse.SCHEMA_VERSION,
                    com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionResponse.SCHEMA_VERSION_V3,
                    com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionResponse.SCHEMA_VERSION_V4,
                    com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionResponse.SCHEMA_VERSION_V5,
                    com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionResponse.SCHEMA_VERSION_V6));
            objects.put("testSuiteRunEvidence", List.of(
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence.SCHEMA_VERSION,
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV2.SCHEMA_VERSION,
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV3.SCHEMA_VERSION,
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV4.SCHEMA_VERSION,
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV5.SCHEMA_VERSION));
            objects.put("testSuiteRunAttestation", List.of(
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation.SCHEMA_VERSION,
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation.SCHEMA_VERSION_V2,
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation.SCHEMA_VERSION_V3,
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation.SCHEMA_VERSION_V4,
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation.SCHEMA_VERSION_V5));
            objects.put("testSuiteEvidenceBundle", List.of(
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteEvidenceBundle.SCHEMA_VERSION,
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteEvidenceBundle.SCHEMA_VERSION_V2,
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteEvidenceBundle.SCHEMA_VERSION_V3,
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteEvidenceBundle.SCHEMA_VERSION_V4,
                    com.leanowtech.bloge.gateway.testing.domain.TestSuiteEvidenceBundle.SCHEMA_VERSION_V5));
            objects.put("semanticCorrectnessWorkbookBundle", List.of(
                    SemanticCorrectnessWorkbookBundle.SCHEMA_VERSION));
            objects.put("testSuiteRunReconciliation", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestSuiteRunReconciliationResult.SCHEMA_VERSION));
            objects.put("testSuiteCatalogMaterialization", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestSuiteCatalogMaterializationResponse.SCHEMA_VERSION));
            objects.put("fixtureBundle", List.of(
                    com.leanowtech.bloge.gateway.testing.domain.FixtureBundle.SCHEMA_VERSION));
            objects.put("fixtureExecutionServices", List.of(
                    com.leanowtech.bloge.gateway.testing.domain.FixtureExecutionServices
                            .SCHEMA_VERSION,
                    com.leanowtech.bloge.gateway.testing.domain.FixtureExecutionServices
                            .SCHEMA_VERSION_V2));
            objects.put("testSecretAuthorityRequest", List.of(
                    TestSecretAuthorityRequest.SCHEMA_VERSION));
            objects.put("testSecretAuthorityResponse", List.of(
                    TestSecretAuthorityResponse.SCHEMA_VERSION));
            objects.put("testSecretAuthorityTrustDescriptor", List.of(
                    TestSecretAuthorityTrustStore.Descriptor.SCHEMA_VERSION));
            objects.put("testSecretAuthorityTrustRefreshSnapshot", List.of(
                    DynamicJwksTestSecretAuthorityTrustStore.RefreshSnapshot.SCHEMA_VERSION));
            objects.put("testSecretAuthorityTrustCohortSnapshot", List.of(
                    TestSecretAuthorityTrustCohortRepository.Snapshot.SCHEMA_VERSION));
            objects.put("testSecretAuthorityTrustCohortDescriptor", List.of(
                    TestSecretAuthorityTrustCohortGate.Descriptor.SCHEMA_VERSION));
            objects.put("testSecretAuthorityServingInventory", List.of(
                    TestSecretAuthorityServingInventory.SCHEMA_VERSION));
            objects.put("testSecretAuthorityServingInventoryMaterial", List.of(
                    TestSecretAuthorityServingInventory.Material.SCHEMA_VERSION));
            objects.put("testSecretAuthorityServingInventoryObservation", List.of(
                    TestSecretAuthorityServingInventoryAuthority.Observation.SCHEMA_VERSION));
            objects.put("testSecretAuthorityServingInventoryDescriptor", List.of(
                    TestSecretAuthorityServingInventoryAuthority.Descriptor.SCHEMA_VERSION));
            objects.put("testSecretAuthorityServingInventoryAttestation", List.of(
                    TestSecretAuthorityTrustCohortPolicy.ServingInventoryAttestation
                            .SCHEMA_VERSION));
            objects.put("testSecretAuthorityServingInventoryPublication", List.of(
                    TestSecretAuthorityServingInventoryPublication.SCHEMA_VERSION));
            objects.put("testSecretAuthorityServingInventoryPublicationMaterial", List.of(
                    TestSecretAuthorityServingInventoryPublication.Material.SCHEMA_VERSION));
            objects.put("testSecretAuthorityServingInventoryWitness", List.of(
                    TestSecretAuthorityServingInventoryPublication.WitnessCheckpoint
                            .SCHEMA_VERSION));
            objects.put("testSecretAuthorityServingInventoryWitnessMaterial", List.of(
                    TestSecretAuthorityServingInventoryPublication.WitnessMaterial
                            .SCHEMA_VERSION));
            objects.put("testSecretAuthorityServingInventoryPublicationGeneration", List.of(
                    TestSecretAuthorityServingInventoryPublicationFloor.Generation
                            .SCHEMA_VERSION));
            objects.put("testSecretAuthorityServingInventoryRefreshSnapshot", List.of(
                    DynamicTestSecretAuthorityServingInventoryAuthority.Snapshot.SCHEMA_VERSION));
            objects.put("testSecretAuthorityServingInventoryTrustRootPublication", List.of(
                    TestSecretAuthorityServingInventoryTrustRootPublication.SCHEMA_VERSION));
            objects.put("testSecretAuthorityServingInventoryTrustRootMaterial", List.of(
                    TestSecretAuthorityServingInventoryTrustRootPublication.Material
                            .SCHEMA_VERSION));
            objects.put("testSecretAuthorityServingInventoryTrustRootGeneration", List.of(
                    TestSecretAuthorityServingInventoryTrustRootFloor.Generation
                            .SCHEMA_VERSION));
            objects.put("testSecretAuthorityServingInventoryTrustRootSnapshot", List.of(
                    ConfiguredTestSecretAuthorityServingInventoryTrustRootAuthority.Snapshot
                            .SCHEMA_VERSION));
            objects.put("testSecretAuthorityServingInventoryDynamicTrustRootSnapshot", List.of(
                    DynamicTestSecretAuthorityServingInventoryTrustRootAuthority.Snapshot
                            .SCHEMA_VERSION));
            objects.put("testSecretAuthorityDescriptor", List.of(
                    TestSecretAuthority.Descriptor.SCHEMA_VERSION));
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
            objects.put("testMutationCasePlan", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestMutationCasePlan.SCHEMA_VERSION));
            objects.put("testBoundarySuiteMaterializationRequest", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestBoundarySuiteMaterializationRequest.SCHEMA_VERSION));
            objects.put("testBoundarySuiteMaterialization", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestBoundarySuiteMaterializationResponse.SCHEMA_VERSION));
            objects.put("testPropertySuiteMaterializationRequest", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestPropertySuiteMaterializationRequest.SCHEMA_VERSION));
            objects.put("testPropertySuiteMaterialization", List.of(
                    com.leanowtech.bloge.gateway.testing.api.TestPropertySuiteMaterializationResponse.SCHEMA_VERSION));
            objects.put("testMutationSuiteMaterializationRequest", List.of(
                    com.leanowtech.bloge.gateway.testing.api
                            .TestMutationSuiteMaterializationRequest.SCHEMA_VERSION));
            objects.put("testMutationSuiteMaterialization", List.of(
                    com.leanowtech.bloge.gateway.testing.api
                            .TestMutationSuiteMaterializationResponse.SCHEMA_VERSION));
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
        features.put("capabilitySnapshotProtocol", true);
        features.put("capabilityProjection", true);
        features.put("capabilityClosureProtocol", true);
        features.put("builtInCapabilityClosureProjection", true);
        features.put("visualCapabilityClosureProjection", true);
        features.put("capabilitySnapshotApi", true);
        features.put("capabilityLifecycleFencing", true);
        features.put("mirrorPlanCompilation", false);
        features.put("mirrorExternalLeafInterception", false);
        features.put("mirrorServing", false);
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
        features.put("externalTestSecretAuthority", false);
        features.put("durableTestSecretReauthorization", false);
        features.put("dynamicTestSecretAuthorityTrust", false);
        features.put("testSecretAuthorityTrustRefreshSlo", false);
        features.put("testSecretAuthorityTrustCohortConvergence", false);
        features.put("testSecretAuthorityTrustCohortReady", false);
        features.put("testSecretAuthorityDeploymentSignedInventory", false);
        features.put("testSecretAuthorityDeploymentSignedInventoryReady", false);
        features.put("testSecretAuthorityDynamicServingInventory", false);
        features.put("testSecretAuthoritySignedInventoryRevocation", false);
        features.put("testSecretAuthorityWitnessedInventoryPublication", false);
        features.put("testSecretAuthorityDurableInventoryPublicationFloor", false);
        features.put("testSecretAuthorityExternallyAnchoredInventoryPublicationFloor", false);
        features.put("testSecretAuthorityByzantineQuorumInventoryPublicationFloor", false);
        features.put("testSecretAuthorityManagedServingInventoryTrustRoots", false);
        features.put("testSecretAuthorityAtomicDualServingInventoryTrustRoots", false);
        features.put("testSecretAuthorityDurableTrustRootFloor", false);
        features.put("testSecretAuthorityExternallyAnchoredTrustRootFloor", false);
        features.put("testSecretAuthorityByzantineQuorumTrustRootFloor", false);
        features.put("testSecretAuthorityExternalNonEquivocationReady", false);
        features.put("testSecretAuthorityManagedTrustRootsReady", false);
        features.put("testSecretAuthorityDynamicServingInventoryReady", false);
        features.put("schemaBoundaryCasePlanning", testExecutionEndpointEnabled);
        features.put("seededPropertyCasePlanning", testExecutionEndpointEnabled);
        features.put("pureDslMutationPlanning", testExecutionEndpointEnabled);
        features.put("pureDslMutationExecution", testExecutionEndpointEnabled);
        features.put("mutationScoreEvidence", testExecutionEndpointEnabled);
        features.put("mutationSuiteMaterialization", testExecutionEndpointEnabled);
        features.put("signedSuiteStabilityAnalysis",
                testExecutionEndpointEnabled && signer.available());
        features.put("idempotentSuiteStabilityRerun",
                testExecutionEndpointEnabled && signer.available());
        features.put("exactBinomialSuiteStabilityConfidence",
                testExecutionEndpointEnabled && signer.available());
        features.put("baselineConditionalSuiteStabilityRateBound",
                testExecutionEndpointEnabled && signer.available());
        features.put("nonZeroSuiteStabilityRateInterval",
                testExecutionEndpointEnabled && signer.available());
        features.put("anytimeValidSuiteStabilityEProcess",
                testExecutionEndpointEnabled && signer.available());
        features.put("signedRetainedSuiteStabilityTrend",
                testExecutionEndpointEnabled && signer.available());
        features.put("crossRetentionSuiteStabilityTrend", false);
        features.put("suiteStabilityCommonCauseConfirmation", false);
        features.put("automaticSuiteQuarantineWorkflow", false);
        features.put("sequentialSuiteStabilityAlphaSpending", false);
        features.put("crossReplicaSuiteStabilityExecutionLease",
                testExecutionEndpointEnabled && signer.available());
        features.put("durableSuiteStabilityParentProgress",
                testExecutionEndpointEnabled && signer.available());
        features.put("asyncSuiteStabilityJobProtocol", testExecutionEndpointEnabled);
        features.put("asyncSuiteStabilityJobSubmission",
                testExecutionEndpointEnabled && suiteStabilityJobSubmissionEnabled);
        features.put("suiteStabilityCurrentAuthorityRevalidation",
                testExecutionEndpointEnabled && suiteStabilityJobSubmissionEnabled
                        && authority.available());
        features.put("signedChallengeBoundSuiteStabilityAuthority",
                testExecutionEndpointEnabled && suiteStabilityJobSubmissionEnabled
                        && authority.available()
                        && Boolean.TRUE.equals(authority.properties().get("signedDecisions"))
                        && Boolean.TRUE.equals(authority.properties().get("challengeBound")));
        features.put("dynamicSuiteStabilityAuthorityTrust",
                testExecutionEndpointEnabled
                        && "DYNAMIC_JWKS_ED25519".equals(
                        authority.properties().get("trustProviderType"))
                        && Boolean.TRUE.equals(
                        authority.properties().get("trustAutomaticRefresh")));
        features.put("suiteStabilityAuthorityTrustRefreshSlo",
                testExecutionEndpointEnabled
                        && "DYNAMIC_JWKS_ED25519".equals(
                        authority.properties().get("trustProviderType"))
                        && Boolean.TRUE.equals(
                        authority.properties().get("trustAutomaticRefresh"))
                        && authority.properties().get("trustRefreshIntervalSeconds")
                        instanceof Number
                        && authority.properties().get("trustMaximumSnapshotAgeSeconds")
                        instanceof Number
                        && Boolean.TRUE.equals(authority.properties().get(
                        "trustFailClosedOnRefreshFailure")));
        features.put("exactSuiteStabilityAuthorityTrustCohort",
                testExecutionEndpointEnabled
                        && Boolean.TRUE.equals(authority.properties().get(
                        "trustCohortConfigured"))
                        && Boolean.TRUE.equals(authority.properties().get(
                        "trustCohortDatabaseAuthority"))
                        && Boolean.TRUE.equals(authority.properties().get(
                        "trustCohortExactConfiguredInventory")));
        features.put("convergedSuiteStabilityAuthorityTrustCohort",
                testExecutionEndpointEnabled
                        && authority.available()
                        && Boolean.TRUE.equals(authority.properties().get(
                        "trustCohortConfigured"))
                        && Boolean.TRUE.equals(authority.properties().get(
                        "trustCohortConverged"))
                        && authority.properties().get("trustCohortExpectedReplicaCount")
                        instanceof Number expected && expected.intValue() > 0
                        && authority.properties().get("trustCohortLiveReplicaCount")
                        instanceof Number live && live.intValue() == expected.intValue()
                        && authority.properties().get("trustCohortHealthyReplicaCount")
                        instanceof Number healthy && healthy.intValue() == expected.intValue()
                        && authority.properties().get("trustCohortDistinctSnapshotCount")
                        instanceof Number generations && generations.intValue() == 1
                        && (!Boolean.TRUE.equals(authority.properties().get(
                        "trustCohortExternallyAttestedInventory"))
                        || authority.properties().get(
                        "trustCohortDistinctServingInventoryGenerationCount")
                        instanceof Number inventoryGenerations
                        && inventoryGenerations.intValue() == 1)
                        && (!Boolean.TRUE.equals(authority.properties().get(
                        "trustCohortDynamicallyRefreshedInventory"))
                        || Boolean.TRUE.equals(authority.properties().get(
                        "trustCohortDurableInventoryPublicationFloor")))
                        && (!Boolean.TRUE.equals(authority.properties().get(
                        "trustCohortManagedInventoryTrustRoots"))
                        || Boolean.TRUE.equals(authority.properties().get(
                        "trustCohortAtomicDualInventoryTrustRootPublication")))
                        && (!Boolean.TRUE.equals(authority.properties().get(
                        "trustCohortByzantineQuorumInventoryNonEquivocation"))
                        || Boolean.TRUE.equals(authority.properties().get(
                        "trustCohortExternalInventoryNonEquivocation"))));
        features.put("externallyAttestedSuiteStabilityServingInventory",
                testExecutionEndpointEnabled
                        && Boolean.TRUE.equals(authority.properties().get(
                        "trustCohortConfigured"))
                        && Boolean.TRUE.equals(authority.properties().get(
                        "trustCohortExternallyAttestedInventory")));
        features.put("dynamicSuiteStabilityServingInventory",
                testExecutionEndpointEnabled
                        && Boolean.TRUE.equals(authority.properties().get(
                        "trustCohortDynamicallyRefreshedInventory")));
        features.put("witnessedSuiteStabilityServingInventoryPublications",
                testExecutionEndpointEnabled
                        && Boolean.TRUE.equals(authority.properties().get(
                        "trustCohortWitnessedInventoryPublications")));
        features.put("durableSuiteStabilityServingInventoryPublicationFloor",
                testExecutionEndpointEnabled
                        && Boolean.TRUE.equals(authority.properties().get(
                        "trustCohortDurableInventoryPublicationFloor")));
        features.put("restartFreeSuiteStabilityServingInventoryKeyRotation",
                testExecutionEndpointEnabled
                        && Boolean.TRUE.equals(authority.properties().get(
                        "trustCohortManagedInventoryTrustRoots"))
                        && Boolean.TRUE.equals(authority.properties().get(
                        "trustCohortAtomicDualInventoryTrustRootPublication")));
        features.put("atomicDualQuorumSuiteStabilityServingInventoryTrustRoots",
                testExecutionEndpointEnabled
                        && Boolean.TRUE.equals(authority.properties().get(
                        "trustCohortManagedInventoryTrustRoots"))
                        && Boolean.TRUE.equals(authority.properties().get(
                        "trustCohortAtomicDualInventoryTrustRootPublication")));
        features.put("externallyAnchoredSuiteStabilityServingInventoryOrdering",
                testExecutionEndpointEnabled
                        && Boolean.TRUE.equals(authority.properties().get(
                        "trustCohortExternalInventoryNonEquivocation")));
        features.put("byzantineQuorumSuiteStabilityServingInventoryNonEquivocation",
                testExecutionEndpointEnabled
                        && Boolean.TRUE.equals(authority.properties().get(
                        "trustCohortExternalInventoryNonEquivocation"))
                        && Boolean.TRUE.equals(authority.properties().get(
                        "trustCohortByzantineQuorumInventoryNonEquivocation")));
        features.put("asyncSuiteStabilityJobQuery", testExecutionEndpointEnabled);
        features.put("asyncSuiteStabilityJobCancellation", testExecutionEndpointEnabled);
        features.put("asyncSuiteStabilityJobCancellationSemanticAudit",
                testExecutionEndpointEnabled);
        features.put("propertySuiteMaterialization", testExecutionEndpointEnabled);
        features.put("propertySuiteExecution", testExecutionEndpointEnabled);
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
        features.put("externalObservationArchiveReconciliationConfigured",
                testExecutionEndpointEnabled && reconciliation.configured());
        features.put("externalObservationArchiveReconciliationReadiness",
                testExecutionEndpointEnabled && reconciliation.ready());
        features.put("boundedExternalObservationArchiveReconciliationHealth",
                testExecutionEndpointEnabled && reconciliation.configured());
        features.put("externalObservationArchiveSourceRetentionConfigured",
                testExecutionEndpointEnabled
                        && reconciliation.sourceRetention().configured());
        features.put("externalObservationArchiveSourceRetentionReadiness",
                testExecutionEndpointEnabled
                        && reconciliation.sourceRetention().ready());
        features.put("boundedExternalObservationArchiveSourceRetentionHealth",
                testExecutionEndpointEnabled
                        && reconciliation.sourceRetention().configured());
        features.put("bootstrapRootRecoveryFleetConfigured", false);
        features.put("bootstrapRootRecoveryFleetReady", false);
        features.put("bootstrapRootRecoveryFleetExternallyAttested", false);
        features.put("bootstrapRootRecoveryFleetDynamicInventory", false);
        features.put("bootstrapRootRecoveryFleetSignedRevocation", false);
        features.put("bootstrapRootRecoveryFleetWitnessedPublications", false);
        features.put("bootstrapRootRecoveryFleetDurablePublicationFloor", false);
        features.put("bootstrapRootRecoveryFleetExternallyAnchoredPublicationFloor", false);
        features.put("bootstrapRootRecoveryFleetByzantineQuorumPublicationFloor", false);
        features.put("bootstrapRootRecoveryFleetManagedTrustRoots", false);
        features.put("bootstrapRootRecoveryFleetManagedTrustRootsReady", false);
        features.put("bootstrapRootRecoveryFleetAtomicDualTrustRoots", false);
        features.put("bootstrapRootRecoveryFleetDurableTrustRootFloor", false);
        features.put("bootstrapRootRecoveryFleetExternallyAnchoredTrustRootFloor", false);
        features.put("bootstrapRootRecoveryFleetByzantineQuorumTrustRootFloor", false);
        features.put("bootstrapRootRecoveryFleetExternalInventoryNonEquivocation", false);
        features.put("bootstrapRootRecoveryFleetByzantineInventoryNonEquivocation", false);
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
                new Endpoint("PUT", "/api/integration/capability-snapshots/{capabilityId}/revisions/{revision}"),
                new Endpoint("GET", "/api/integration/capability-snapshots/{capabilityId}"),
                new Endpoint("POST", "/api/integration/capability-snapshots/{capabilityId}/lifecycle-transitions"),
                new Endpoint("POST", "/api/integration/capability-closures/project"),
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
            endpoints.add(new Endpoint("GET",
                    "/api/testing/targets/graphs/{graphName}/mutation-cases"));
            endpoints.add(new Endpoint("POST",
                    "/api/testing/targets/graphs/{graphName}/mutation-suites"));
            endpoints.add(new Endpoint("POST",
                    "/api/testing/targets/graphs/{graphName}/boundary-suites"));
            endpoints.add(new Endpoint("POST",
                    "/api/testing/targets/graphs/{graphName}/property-suites"));
            endpoints.add(new Endpoint("GET", "/api/testing/targets/operators/{operatorRef}"));
            endpoints.add(new Endpoint("GET",
                    "/api/testing/targets/operators/{operatorRef}/boundary-cases"));
            endpoints.add(new Endpoint("GET",
                    "/api/testing/targets/operators/{operatorRef}/property-cases"));
            endpoints.add(new Endpoint("POST",
                    "/api/testing/targets/operators/{operatorRef}/boundary-suites"));
            endpoints.add(new Endpoint("POST",
                    "/api/testing/targets/operators/{operatorRef}/property-suites"));
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
            endpoints.add(new Endpoint("POST",
                    "/api/testing/suites/{suiteId}/mutation-executions"));
            endpoints.add(new Endpoint("POST",
                    "/api/testing/suites/{suiteId}/stability-executions"));
            endpoints.add(new Endpoint("POST",
                    "/api/testing/suites/{suiteId}/stability-trend-analyses"));
            endpoints.add(new Endpoint("GET",
                    "/api/testing/stability-executions/{stabilityRunId}"));
            endpoints.add(new Endpoint("GET",
                    "/api/testing/stability-executions/{stabilityRunId}/progress"));
            endpoints.add(new Endpoint("POST",
                    "/api/testing/suites/{suiteId}/stability-jobs"));
            endpoints.add(new Endpoint("GET",
                    "/api/testing/stability-jobs/{jobId}"));
            endpoints.add(new Endpoint("POST",
                    "/api/testing/stability-jobs/{jobId}/cancellations"));
            endpoints.add(new Endpoint("GET", "/api/testing/suite-executions/{suiteRunId}"));
            endpoints.add(new Endpoint("GET",
                    "/api/testing/suite-executions/{suiteRunId}/evidence-bundle"));
            endpoints.add(new Endpoint("GET",
                    "/api/integration/test-suites/{suiteId}/revisions/{revision}"
                            + "/semantic-correctness-workbook"));
        }
        return new IntegrationCapabilities("", "", "", objects, features, identityProvider, signer,
                payloadGovernance, testExecutionEndpointEnabled
                        ? Testability.executionControlPlane(
                        changeTrust, suiteStabilityJobSubmissionEnabled, authority, reconciliation)
                        : Testability.schemaContractOnly(),
                endpoints);
    }

    private static VisualPayloadGovernancePolicy.Descriptor unavailablePayloadGovernance() {
        return new VisualPayloadGovernancePolicy.Descriptor("", "", "", "RESTRICTED",
                false, false, false, true);
    }

    private static TestSuiteStabilityJobAuthorizer.Descriptor unavailableCurrentAuthority() {
        return new TestSuiteStabilityJobAuthorizer.Descriptor(
                "", false, "UNAVAILABLE", "", Map.of());
    }

    private static TestSuiteStabilityJobAuthorizer.Descriptor customCurrentAuthority() {
        return new TestSuiteStabilityJobAuthorizer.Descriptor(
                "", true, "CUSTOM_UNDECLARED", "", Map.of(
                "signedDecisions", false,
                "challengeBound", false,
                "privateMaterialPresent", false));
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
     * @param suiteStabilityJobSubmissionEnabled whether fresh asynchronous stability jobs can run
     * @param workerQuarantineChangeAuthorizationTrust key-free external approval readiness
     * @param suiteStabilityCurrentAuthority key-free current-authority revalidation readiness
     * @param externalArchiveReconciliation identity-free reconciliation operational readiness
     * @param recoveryFleet identity-free bootstrap-root recovery-fleet readiness
     * @param physicalAttemptRuntime identity-free physical-attempt industrial readiness
     */
    public record Testability(
            String protocolVersion,
            List<String> enabledEnvironments,
            boolean schemaContractMode,
            boolean executionEndpointEnabled,
            boolean suiteStabilityJobSubmissionEnabled,
            WorkerQuarantineChangeAuthorizationTrustStore.Descriptor
                    workerQuarantineChangeAuthorizationTrust,
            TestSuiteStabilityJobAuthorizer.Descriptor suiteStabilityCurrentAuthority,
            TestSuiteStabilityObservationExternalArchiveReconciliationHealth.Descriptor
                    externalArchiveReconciliation,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability recoveryFleet,
            TestSuiteStabilityPhysicalAttemptRuntimeCapability physicalAttemptRuntime
    ) {
        /** Normalizes capability values. */
        public Testability {
            if (suiteStabilityJobSubmissionEnabled && !executionEndpointEnabled) {
                throw new IllegalArgumentException(
                        "Stability-job submission requires execution control plane");
            }
            protocolVersion = protocolVersion == null || protocolVersion.isBlank()
                    ? "bloge.testing.v1" : protocolVersion.trim();
            enabledEnvironments = enabledEnvironments == null
                    ? List.of() : List.copyOf(enabledEnvironments);
            workerQuarantineChangeAuthorizationTrust =
                    workerQuarantineChangeAuthorizationTrust == null
                            ? WorkerQuarantineChangeAuthorizationTrustStore.unavailable().descriptor()
                            : workerQuarantineChangeAuthorizationTrust;
            suiteStabilityCurrentAuthority = suiteStabilityCurrentAuthority == null
                    ? unavailableCurrentAuthority() : suiteStabilityCurrentAuthority;
            externalArchiveReconciliation = externalArchiveReconciliation == null
                    ? TestSuiteStabilityObservationExternalArchiveReconciliationHealth.Descriptor
                    .unavailable() : externalArchiveReconciliation;
            recoveryFleet = recoveryFleet == null
                    ? ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.disabled()
                    : recoveryFleet;
            physicalAttemptRuntime = physicalAttemptRuntime == null
                    ? TestSuiteStabilityPhysicalAttemptRuntimeCapability.disabled()
                    : physicalAttemptRuntime;
            if (suiteStabilityJobSubmissionEnabled
                    && !suiteStabilityCurrentAuthority.available()) {
                throw new IllegalArgumentException(
                        "Stability-job submission requires current-authority readiness");
            }
        }

        /**
         * Preserves the pre-recovery-fleet capability constructor.
         *
         * @param protocolVersion testing-control protocol generation
         * @param enabledEnvironments environments allowed to expose the testing control plane
         * @param schemaContractMode whether schema-only contracts are available
         * @param executionEndpointEnabled whether synchronous test execution is available
         * @param suiteStabilityJobSubmissionEnabled whether asynchronous suite submission is ready
         * @param trust key-free worker-quarantine authorization trust readiness
         * @param currentAuthority key-free current-authority revalidation readiness
         * @param archiveReconciliation external archive reconciliation readiness
         */
        public Testability(
                String protocolVersion,
                List<String> enabledEnvironments,
                boolean schemaContractMode,
                boolean executionEndpointEnabled,
                boolean suiteStabilityJobSubmissionEnabled,
                WorkerQuarantineChangeAuthorizationTrustStore.Descriptor trust,
                TestSuiteStabilityJobAuthorizer.Descriptor currentAuthority,
                TestSuiteStabilityObservationExternalArchiveReconciliationHealth.Descriptor
                        archiveReconciliation) {
            this(protocolVersion, enabledEnvironments, schemaContractMode,
                    executionEndpointEnabled, suiteStabilityJobSubmissionEnabled, trust,
                    currentAuthority, archiveReconciliation,
                    ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.disabled(),
                    TestSuiteStabilityPhysicalAttemptRuntimeCapability.disabled());
        }

        /**
         * Preserves the pre-physical-attempt capability constructor.
         *
         * @param protocolVersion testing-control protocol generation
         * @param enabledEnvironments environments allowed to expose the testing control plane
         * @param schemaContractMode whether schema-only contracts are available
         * @param executionEndpointEnabled whether synchronous test execution is available
         * @param suiteStabilityJobSubmissionEnabled whether asynchronous suite submission is ready
         * @param trust key-free worker-quarantine authorization trust readiness
         * @param currentAuthority key-free current-authority revalidation readiness
         * @param archiveReconciliation external archive reconciliation readiness
         * @param recoveryFleet bootstrap-root recovery-fleet readiness
         */
        public Testability(
                String protocolVersion,
                List<String> enabledEnvironments,
                boolean schemaContractMode,
                boolean executionEndpointEnabled,
                boolean suiteStabilityJobSubmissionEnabled,
                WorkerQuarantineChangeAuthorizationTrustStore.Descriptor trust,
                TestSuiteStabilityJobAuthorizer.Descriptor currentAuthority,
                TestSuiteStabilityObservationExternalArchiveReconciliationHealth.Descriptor
                        archiveReconciliation,
                ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability recoveryFleet) {
            this(protocolVersion, enabledEnvironments, schemaContractMode,
                    executionEndpointEnabled, suiteStabilityJobSubmissionEnabled, trust,
                    currentAuthority, archiveReconciliation, recoveryFleet,
                    TestSuiteStabilityPhysicalAttemptRuntimeCapability.disabled());
        }

        /** Preserves the v1 constructor while treating asynchronous submission as unavailable. */
        public Testability(
                String protocolVersion,
                List<String> enabledEnvironments,
                boolean schemaContractMode,
                boolean executionEndpointEnabled,
                    WorkerQuarantineChangeAuthorizationTrustStore.Descriptor trust) {
            this(protocolVersion, enabledEnvironments, schemaContractMode,
                    executionEndpointEnabled, false, trust, unavailableCurrentAuthority(),
                    TestSuiteStabilityObservationExternalArchiveReconciliationHealth.Descriptor
                            .unavailable());
        }

        /** Preserves the pre-reconciliation constructor with an explicit disabled descriptor. */
        public Testability(
                String protocolVersion,
                List<String> enabledEnvironments,
                boolean schemaContractMode,
                boolean executionEndpointEnabled,
                boolean suiteStabilityJobSubmissionEnabled,
                WorkerQuarantineChangeAuthorizationTrustStore.Descriptor trust,
                TestSuiteStabilityJobAuthorizer.Descriptor currentAuthority) {
            this(protocolVersion, enabledEnvironments, schemaContractMode,
                    executionEndpointEnabled, suiteStabilityJobSubmissionEnabled, trust,
                    currentAuthority,
                    TestSuiteStabilityObservationExternalArchiveReconciliationHealth.Descriptor
                            .unavailable());
        }

        /** @return Stage 0 capability before the execution endpoint is activated */
        public static Testability schemaContractOnly() {
            return new Testability("bloge.testing.v1", List.of("test", "staging"),
                    true, false, false,
                    WorkerQuarantineChangeAuthorizationTrustStore.unavailable().descriptor(),
                    unavailableCurrentAuthority(),
                    TestSuiteStabilityObservationExternalArchiveReconciliationHealth.Descriptor
                            .unavailable());
        }

        /** @return Stage 2 capability with the caller-driven execution endpoint assembled */
        public static Testability executionControlPlane() {
            return executionControlPlane(
                    WorkerQuarantineChangeAuthorizationTrustStore.unavailable().descriptor());
        }

        /** @return execution capability with exact external approval trust readiness */
        public static Testability executionControlPlane(
                WorkerQuarantineChangeAuthorizationTrustStore.Descriptor trust) {
            return executionControlPlane(trust, false);
        }

        /** @return execution capability with exact asynchronous submission availability */
        public static Testability executionControlPlane(
                WorkerQuarantineChangeAuthorizationTrustStore.Descriptor trust,
                boolean suiteStabilityJobSubmissionEnabled) {
            return executionControlPlane(trust, suiteStabilityJobSubmissionEnabled,
                    suiteStabilityJobSubmissionEnabled
                            ? customCurrentAuthority()
                            : unavailableCurrentAuthority());
        }

        /** @return execution capability with exact current-authority readiness */
        public static Testability executionControlPlane(
                WorkerQuarantineChangeAuthorizationTrustStore.Descriptor trust,
                boolean suiteStabilityJobSubmissionEnabled,
                TestSuiteStabilityJobAuthorizer.Descriptor currentAuthority) {
            return executionControlPlane(trust, suiteStabilityJobSubmissionEnabled,
                    currentAuthority,
                    TestSuiteStabilityObservationExternalArchiveReconciliationHealth.Descriptor
                            .unavailable());
        }

        /** @return execution capability with exact reconciliation readiness */
        public static Testability executionControlPlane(
                WorkerQuarantineChangeAuthorizationTrustStore.Descriptor trust,
                boolean suiteStabilityJobSubmissionEnabled,
                TestSuiteStabilityJobAuthorizer.Descriptor currentAuthority,
                TestSuiteStabilityObservationExternalArchiveReconciliationHealth.Descriptor
                        archiveReconciliation) {
            return new Testability("bloge.testing.v1", List.of("test", "staging"),
                    true, true, suiteStabilityJobSubmissionEnabled, trust, currentAuthority,
                    archiveReconciliation);
        }

        /**
         * Returns this protocol state with a freshly projected local recovery-fleet capability.
         *
         * @param capability current identity-free fleet readiness
         * @return immutable testability projection
         */
        public Testability withRecoveryFleet(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability capability) {
            return new Testability(protocolVersion, enabledEnvironments, schemaContractMode,
                    executionEndpointEnabled, suiteStabilityJobSubmissionEnabled,
                    workerQuarantineChangeAuthorizationTrust, suiteStabilityCurrentAuthority,
                    externalArchiveReconciliation, Objects.requireNonNull(capability, "capability"),
                    physicalAttemptRuntime);
        }

        /**
         * Returns this protocol state with current physical-attempt industrial readiness.
         *
         * @param capability identity-free signed-inventory and runtime readiness
         * @return immutable testability projection
         */
        public Testability withPhysicalAttemptRuntime(
                TestSuiteStabilityPhysicalAttemptRuntimeCapability capability) {
            return new Testability(protocolVersion, enabledEnvironments, schemaContractMode,
                    executionEndpointEnabled, suiteStabilityJobSubmissionEnabled,
                    workerQuarantineChangeAuthorizationTrust, suiteStabilityCurrentAuthority,
                    externalArchiveReconciliation, recoveryFleet,
                    Objects.requireNonNull(capability, "capability"));
        }
    }
}
