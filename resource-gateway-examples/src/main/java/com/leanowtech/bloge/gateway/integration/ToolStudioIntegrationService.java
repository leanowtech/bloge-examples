package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneCertificateRotationEventWatcher;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneCertificateRotationRuntime;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneCertificateStatusSloMonitor;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootTrustStore;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorReceiptTrustStore;
import com.leanowtech.bloge.gateway.testing.domain.WorkerQuarantineRequestIndexMode;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobAuthorizer;
import com.leanowtech.bloge.gateway.testing.api.TestSecretAuthority;
import com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityExternalSequenceAnchor;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExternalSequenceAnchor;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveReconciliationHealth;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptObservationReconciliationHealth;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptProviderInventoryCohortGate;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptRuntimeCapability;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptTerminalProjectionHealth;
import com.leanowtech.bloge.gateway.testing.api.WorkerQuarantineChangeAuthorizationTrustStore;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchScheduler;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftDependencyReport;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRecord;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRepository;
import com.leanowtech.bloge.gateway.visual.runtime.EvidenceVerificationKeySet;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualReplayAssertionResult;
import com.leanowtech.bloge.gateway.visual.runtime.VisualReplayMetadata;
import com.leanowtech.bloge.gateway.visual.runtime.VisualPayloadGovernanceException;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunPayloadRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunPayloadStatus;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Stable integration projection over existing visual authoring services.
 */
@Service
public class ToolStudioIntegrationService {

    private final GraphDraftRepository draftRepository;
    private final GraphDraftValidator validator;
    private final VisualOperatorCatalog catalog;
    private final VisualGraphRunRepository runRepository;
    private final GovernanceGateResultRepository gateResultRepository;
    private final ReplayAssertionEvaluator replayAssertionEvaluator;
    private final IntegrationIdentityResolver identityResolver;
    private final SideEffectReconcilerRegistry sideEffectReconcilers;
    private final GraphDraftDependencySnapshotService dependencySnapshots;
    private final CorrectnessWorkbookProjectionService workbookProjection;
    private SemanticCorrectnessWorkbookProjectionService semanticWorkbookProjection;
    private SemanticGateTargetVerifier semanticGateTargetVerifier;
    private EvidenceKeySetTrustStore evidenceTrustStore = EvidenceKeySetTrustStore.unavailable();
    private EvidenceKeySetTrustPublicationRepository evidenceTrustPublications;
    private final ObjectMapper objectMapper;
    private boolean testExecutionEndpointEnabled;
    private boolean suiteStabilityJobSubmissionEnabled;
    private MirrorRuntimeAvailability mirrorRuntimeAvailability =
            new MirrorRuntimeAvailability(false, false);
    private ScenarioRehearsalBatchScheduler
            scenarioRehearsalBatchScheduler;
    private MirrorStatefulRuntimeAvailability mirrorStatefulRuntimeAvailability =
            new MirrorStatefulRuntimeAvailability(false, () -> false);
    private WorkerQuarantineRequestIndexMode workerQuarantineRequestIndexMode;
    private WorkerQuarantineChangeAuthorizationTrustStore.Descriptor
            workerQuarantineChangeAuthorizationTrust =
            WorkerQuarantineChangeAuthorizationTrustStore.unavailable().descriptor();
    private TestSuiteStabilityJobAuthorizer.Descriptor suiteStabilityCurrentAuthority =
            new TestSuiteStabilityJobAuthorizer.Descriptor(
                    "", false, "UNAVAILABLE", "", Map.of());
    private ObjectProvider<TestSuiteStabilityJobAuthorizer> suiteStabilityAuthorizers;
    private ObjectProvider<TestSecretAuthority> testSecretAuthorities;
    private ObjectProvider<TestSuiteStabilityExternalSequenceAnchor>
            suiteStabilityExternalSequenceAnchors;
    private ObjectProvider<TestSecretAuthorityExternalSequenceAnchor>
            testSecretAuthorityExternalSequenceAnchors;
    private TestSuiteStabilityObservationExternalArchiveReconciliationHealth
            externalArchiveReconciliationHealth;
    private ControlPlaneCertificateRotationRuntime controlPlaneCertificateRotationRuntime;
    private ControlPlaneCertificateRotationEventWatcher
            controlPlaneCertificateRotationEventWatcher;
    private ControlPlaneCertificateStatusSloMonitor controlPlaneCertificateStatusSloMonitor;
    private List<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory>
            recoveryFleetInventories = List.of();
    private List<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority>
            recoveryFleetAuthorities = List.of();
    private List<ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker>
            recoveryFleetWorkers = List.of();
    private List<ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler>
            recoveryFleetSchedulers = List.of();
    private List<TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority>
            physicalAttemptProviderInventories = List.of();
    private List<TestSuiteStabilityPhysicalAttemptProviderInventoryCohortGate>
            physicalAttemptInventoryCohorts = List.of();
    private List<TestSuiteStabilityPhysicalAttemptObservationReconciliationHealth>
            physicalAttemptReconciliationHealth = List.of();
    private List<TestSuiteStabilityPhysicalAttemptTerminalProjectionHealth>
            physicalAttemptTerminalHealth = List.of();

    @Autowired
    public ToolStudioIntegrationService(GraphDraftRepository draftRepository,
                                        GraphDraftValidator validator,
                                        VisualOperatorCatalog catalog,
                                        VisualGraphRunRepository runRepository,
                                        GovernanceGateResultRepository gateResultRepository,
                                        ObjectMapper objectMapper,
                                        IntegrationIdentityResolver identityResolver,
                                        SideEffectReconcilerRegistry sideEffectReconcilers,
                                        GraphDraftDependencySnapshotService dependencySnapshots,
                                        CorrectnessWorkbookProjectionService workbookProjection) {
        this.draftRepository = draftRepository;
        this.validator = validator;
        this.catalog = catalog;
        this.runRepository = runRepository;
        this.gateResultRepository = gateResultRepository;
        this.objectMapper = objectMapper == null
                ? new ObjectMapper().findAndRegisterModules() : objectMapper;
        this.replayAssertionEvaluator = new ReplayAssertionEvaluator(this.objectMapper);
        this.identityResolver = identityResolver == null
                ? IntegrationIdentityResolver.unavailable()
                : identityResolver;
        this.sideEffectReconcilers = sideEffectReconcilers == null
                ? new SideEffectReconcilerRegistry(List.of()) : sideEffectReconcilers;
        this.dependencySnapshots = dependencySnapshots == null
                ? new GraphDraftDependencySnapshotService(catalog) : dependencySnapshots;
        this.workbookProjection = workbookProjection;
    }

    /** Receives the profile-owned marker only when the isolated test control plane is assembled. */
    @Autowired(required = false)
    void configureTestability(TestabilityAvailability availability) {
        this.testExecutionEndpointEnabled = availability != null && availability.executionEndpointEnabled();
        this.suiteStabilityJobSubmissionEnabled = availability != null
                && availability.suiteStabilityJobSubmissionEnabled();
        this.workerQuarantineRequestIndexMode = this.testExecutionEndpointEnabled
                ? availability.workerQuarantineRequestIndexMode() : null;
        this.workerQuarantineChangeAuthorizationTrust = availability == null
                ? WorkerQuarantineChangeAuthorizationTrustStore.unavailable().descriptor()
                : availability.workerQuarantineChangeAuthorizationTrust();
        this.suiteStabilityCurrentAuthority = availability == null
                ? new TestSuiteStabilityJobAuthorizer.Descriptor(
                "", false, "UNAVAILABLE", "", Map.of())
                : availability.suiteStabilityCurrentAuthority();
    }

    /** Receives the marker only when protected mirror routes are physically assembled. */
    @Autowired(required = false)
    void configureMirrorRuntime(MirrorRuntimeAvailability availability) {
        this.mirrorRuntimeAvailability = availability == null
                ? new MirrorRuntimeAvailability(false, false) : availability;
    }

    /** Receives the scheduler only when an exact non-production batch partition is active. */
    @Autowired(required = false)
    void configureScenarioRehearsalBatchScheduler(
            ScenarioRehearsalBatchScheduler scheduler) {
        this.scenarioRehearsalBatchScheduler = scheduler;
    }

    /** Receives the marker only when encrypted stateful Session routes are assembled. */
    @Autowired(required = false)
    void configureMirrorStatefulRuntime(
            MirrorStatefulRuntimeAvailability availability) {
        this.mirrorStatefulRuntimeAvailability = availability == null
                ? new MirrorStatefulRuntimeAvailability(false, () -> false)
                : availability;
    }

    /** Resolves time-sensitive current-authority readiness on every capability request. */
    @Autowired
    void configureSuiteStabilityAuthorizers(
            ObjectProvider<TestSuiteStabilityJobAuthorizer> authorizers) {
        this.suiteStabilityAuthorizers = authorizers;
    }

    /** Resolves external test-secret readiness on every capability request. */
    @Autowired
    void configureTestSecretAuthorities(ObjectProvider<TestSecretAuthority> authorities) {
        this.testSecretAuthorities = authorities;
    }

    /** Resolves managed suite-stability notary trust readiness on every capability request. */
    @Autowired
    void configureSuiteStabilityExternalSequenceAnchors(
            ObjectProvider<TestSuiteStabilityExternalSequenceAnchor> anchors) {
        this.suiteStabilityExternalSequenceAnchors = anchors;
    }

    /** Resolves managed test-secret notary trust readiness on every capability request. */
    @Autowired
    void configureTestSecretAuthorityExternalSequenceAnchors(
            ObjectProvider<TestSecretAuthorityExternalSequenceAnchor> anchors) {
        this.testSecretAuthorityExternalSequenceAnchors = anchors;
    }

    /**
     * Freezes recovery-fleet bean candidates at startup so capability reads never instantiate a
     * lazy authority and accidentally perform remote bootstrap I/O.
     *
     * @param inventories local inventory candidates
     * @param authorities externally attested inventory-authority candidates
     * @param workers bounded recovery-worker candidates
     * @param schedulers fixed-delay recovery-scheduler candidates
     */
    @Autowired
    void configureRecoveryFleetCapabilitySources(
            ObjectProvider<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory> inventories,
            ObjectProvider<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority>
                    authorities,
            ObjectProvider<ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker> workers,
            ObjectProvider<ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler> schedulers) {
        recoveryFleetInventories = inventories.orderedStream().toList();
        recoveryFleetAuthorities = authorities.orderedStream().toList();
        recoveryFleetWorkers = workers.orderedStream().toList();
        recoveryFleetSchedulers = schedulers.orderedStream().toList();
    }

    /**
     * Freezes physical-attempt aggregate sources so capability reads cannot instantiate a lazy
     * provider or trigger provider/network I/O.
     *
     * @param inventories signed provider-inventory candidates
     * @param cohorts durable cross-replica convergence candidates
     * @param reconciliationHealth observation-reconciliation readiness candidates
     * @param terminalHealth terminal-projection readiness candidates
     */
    @Autowired
    void configurePhysicalAttemptCapabilitySources(
            ObjectProvider<TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority>
                    inventories,
            ObjectProvider<TestSuiteStabilityPhysicalAttemptProviderInventoryCohortGate> cohorts,
            ObjectProvider<TestSuiteStabilityPhysicalAttemptObservationReconciliationHealth>
                    reconciliationHealth,
            ObjectProvider<TestSuiteStabilityPhysicalAttemptTerminalProjectionHealth>
                    terminalHealth) {
        physicalAttemptProviderInventories = inventories.orderedStream().toList();
        physicalAttemptInventoryCohorts = cohorts.orderedStream().toList();
        physicalAttemptReconciliationHealth = reconciliationHealth.orderedStream().toList();
        physicalAttemptTerminalHealth = terminalHealth.orderedStream().toList();
    }

    /** Receives the explicit test/staging reconciliation readiness monitor when assembled. */
    @Autowired(required = false)
    void configureExternalArchiveReconciliationHealth(
            TestSuiteStabilityObservationExternalArchiveReconciliationHealth health) {
        this.externalArchiveReconciliationHealth = health;
    }

    /** Receives the profile-owned local certificate-rotation runtime when assembled. */
    @Autowired(required = false)
    void configureControlPlaneCertificateRotation(
            ControlPlaneCertificateRotationRuntime runtime) {
        this.controlPlaneCertificateRotationRuntime = runtime;
    }

    /** Receives the profile-owned durable certificate-rotation event watcher when assembled. */
    @Autowired(required = false)
    void configureControlPlaneCertificateRotationEventWatcher(
            ControlPlaneCertificateRotationEventWatcher watcher) {
        this.controlPlaneCertificateRotationEventWatcher = watcher;
    }

    /** Receives the profile-owned fixed-cardinality certificate-status SLO monitor. */
    @Autowired(required = false)
    void configureControlPlaneCertificateStatusSloMonitor(
            ControlPlaneCertificateStatusSloMonitor monitor) {
        this.controlPlaneCertificateStatusSloMonitor = monitor;
    }

    /** Receives the profile-owned semantic workbook projector with the isolated test runtime. */
    @Autowired(required = false)
    void configureSemanticWorkbookProjection(
            SemanticCorrectnessWorkbookProjectionService semanticWorkbookProjection) {
        this.semanticWorkbookProjection = semanticWorkbookProjection;
    }

    /** Receives the compiler-backed semantic target verifier with the isolated test runtime. */
    @Autowired(required = false)
    void configureSemanticGateTargetVerifier(
            SemanticGateTargetVerifier semanticGateTargetVerifier) {
        this.semanticGateTargetVerifier = semanticGateTargetVerifier;
    }

    /** Receives independently configured governance trust anchors and the durable transparency log. */
    @Autowired(required = false)
    void configureEvidenceTrust(
            EvidenceKeySetTrustStore trustStore,
            EvidenceKeySetTrustPublicationRepository publications) {
        this.evidenceTrustStore = trustStore == null
                ? EvidenceKeySetTrustStore.unavailable() : trustStore;
        this.evidenceTrustPublications = publications;
    }

    public ToolStudioIntegrationService(GraphDraftRepository draftRepository,
                                        GraphDraftValidator validator,
                                        VisualOperatorCatalog catalog,
                                        VisualGraphRunRepository runRepository,
                                        GovernanceGateResultRepository gateResultRepository,
                                        ObjectMapper objectMapper,
                                        IntegrationIdentityResolver identityResolver,
                                        SideEffectReconcilerRegistry sideEffectReconcilers,
                                        GraphDraftDependencySnapshotService dependencySnapshots) {
        this(draftRepository, validator, catalog, runRepository, gateResultRepository, objectMapper,
                identityResolver, sideEffectReconcilers, dependencySnapshots, null);
    }

    public ToolStudioIntegrationService(GraphDraftRepository draftRepository,
                                        GraphDraftValidator validator,
                                        VisualOperatorCatalog catalog,
                                        VisualGraphRunRepository runRepository,
                                        GovernanceGateResultRepository gateResultRepository,
                                        ObjectMapper objectMapper,
                                        IntegrationIdentityResolver identityResolver,
                                        SideEffectReconcilerRegistry sideEffectReconcilers) {
        this(draftRepository, validator, catalog, runRepository, gateResultRepository, objectMapper,
                identityResolver, sideEffectReconcilers, null, null);
    }

    public ToolStudioIntegrationService(GraphDraftRepository draftRepository,
                                        GraphDraftValidator validator,
                                        VisualOperatorCatalog catalog,
                                        VisualGraphRunRepository runRepository,
                                        GovernanceGateResultRepository gateResultRepository,
                                        ObjectMapper objectMapper,
                                        IntegrationIdentityResolver identityResolver) {
        this(draftRepository, validator, catalog, runRepository, gateResultRepository, objectMapper,
                identityResolver, new SideEffectReconcilerRegistry(List.of()), null, null);
    }

    public ToolStudioIntegrationService(GraphDraftRepository draftRepository,
                                        GraphDraftValidator validator,
                                        VisualOperatorCatalog catalog,
                                        VisualGraphRunRepository runRepository,
                                        GovernanceGateResultRepository gateResultRepository,
                                        ObjectMapper objectMapper) {
        this(draftRepository, validator, catalog, runRepository, gateResultRepository, objectMapper,
                IntegrationIdentityResolver.unavailable(), new SideEffectReconcilerRegistry(List.of()), null, null);
    }

    public ToolStudioIntegrationService(GraphDraftRepository draftRepository,
                                        GraphDraftValidator validator,
                                        VisualOperatorCatalog catalog,
                                        VisualGraphRunRepository runRepository,
                                        GovernanceGateResultRepository gateResultRepository) {
        this(draftRepository, validator, catalog, runRepository, gateResultRepository,
                new ObjectMapper().findAndRegisterModules());
    }

    public ToolStudioIntegrationService(GraphDraftRepository draftRepository,
                                        GraphDraftValidator validator,
                                        VisualOperatorCatalog catalog,
                                        VisualGraphRunRepository runRepository) {
        this(draftRepository, validator, catalog, runRepository, new InMemoryGovernanceGateResultRepository());
    }

    public IntegrationEnvelope<IntegrationCapabilities> capabilities() {
        boolean mirrorPlanReady = mirrorRuntimeAvailability.planCompilationApi();
        boolean mirrorExecutionApi = mirrorRuntimeAvailability.executionApi();
        boolean mirrorExecutionReady = mirrorRuntimeAvailability.executionReady();
        boolean mirrorStatefulSessionApi =
                mirrorStatefulRuntimeAvailability.sessionApi();
        boolean mirrorStatefulStoreReady =
                mirrorStatefulRuntimeAvailability.stateStoreReady();
        boolean mirrorCheckpointReady =
                mirrorStatefulRuntimeAvailability.checkpointReady();
        boolean mirrorWriteAttemptReconciliationReady =
                mirrorStatefulRuntimeAvailability
                        .writeAttemptReconciliationReady();
        boolean mirrorStatefulResolverReady = mirrorExecutionReady
                && mirrorStatefulSessionApi && mirrorStatefulStoreReady;
        VisualEvidenceSigner signer = runRepository == null
                ? VisualEvidenceSigner.unavailable() : runRepository.evidenceSigner();
        VisualRunPayloadRepository payloads = runRepository == null ? null : runRepository.payloadRepository();
        TestSuiteStabilityJobAuthorizer.Descriptor currentAuthority =
                currentSuiteStabilityAuthority();
        boolean stabilitySubmissionReady = suiteStabilityJobSubmissionEnabled
                && currentAuthority.available();
        TestSuiteStabilityObservationExternalArchiveReconciliationHealth.Descriptor
                archiveReconciliation = currentExternalArchiveReconciliation();
        IntegrationCapabilities current = IntegrationCapabilities.current(
                        signer.descriptor(), identityResolver.descriptor(),
                        sideEffectReconcilers.available(), payloads == null ? null : payloads.policyDescriptor(),
                        testExecutionEndpointEnabled, evidenceTrustStore.descriptor(),
                        workerQuarantineRequestIndexMode,
                        workerQuarantineChangeAuthorizationTrust,
                        stabilitySubmissionReady,
                        currentAuthority,
                        archiveReconciliation);
        TestSecretAuthority.Descriptor testSecretAuthority = currentTestSecretAuthority();
        boolean secretAuthorityReady = testSecretAuthority.available();
        Map<String, Boolean> features = new LinkedHashMap<>(current.features());
        features.put("mirrorPlanCompilation", mirrorPlanReady);
        features.put("mirrorExternalLeafInterception", mirrorPlanReady);
        features.put("mirrorScenarioArtifactRegistry", mirrorPlanReady);
        features.put("mirrorScenarioRehearsalCompilation", mirrorPlanReady);
        features.put("mirrorScenarioRehearsalExecution",
                mirrorPlanReady && mirrorExecutionApi);
        features.put("mirrorScenarioRehearsalEvidenceApi",
                mirrorPlanReady && mirrorExecutionApi);
        features.put("mirrorScenarioRehearsalRetentionApi",
                mirrorPlanReady && mirrorExecutionApi);
        features.put("mirrorScenarioRehearsalLegalHold",
                mirrorPlanReady && mirrorExecutionApi);
        features.put("mirrorScenarioRehearsalDeletionProof",
                mirrorPlanReady && mirrorExecutionApi);
        features.put("mirrorScenarioRehearsalWorkbookSeed",
                mirrorPlanReady && mirrorExecutionApi);
        features.put("mirrorScenarioRehearsalBatchApi",
                mirrorPlanReady && mirrorExecutionApi);
        features.put(
                "mirrorScenarioRehearsalBatchCooperativeControl",
                mirrorPlanReady && mirrorExecutionApi);
        features.put(
                "mirrorScenarioRehearsalBatchEvidence",
                mirrorPlanReady && mirrorExecutionApi);
        features.put(
                "mirrorScenarioRehearsalBatchRetentionApi",
                mirrorPlanReady && mirrorExecutionApi);
        features.put(
                "mirrorScenarioRehearsalBatchLegalHold",
                mirrorPlanReady && mirrorExecutionApi);
        features.put(
                "mirrorScenarioRehearsalBatchDeletionProof",
                mirrorPlanReady && mirrorExecutionApi);
        features.put(
                "mirrorScenarioRehearsalBatchScheduling",
                mirrorPlanReady
                        && mirrorExecutionApi
                        && scenarioRehearsalBatchScheduler != null
                        && scenarioRehearsalBatchScheduler.ready());
        features.put("mirrorScenarioRehearsalEvidence", false);
        features.put("mirrorServing", mirrorExecutionReady);
        features.put("mirrorOperationObservability", mirrorPlanReady && mirrorExecutionApi);
        features.put("mirrorIsolationAuthorityDistributionApi",
                mirrorRuntimeAvailability.authorityDistributionApi());
        features.put("mirrorIsolationAuthorityDistributionReady",
                mirrorRuntimeAvailability.authorityDistributionReady());
        features.put("mirrorIsolationAttestationDistributionApi",
                mirrorRuntimeAvailability.attestationDistributionApi());
        features.put("mirrorIsolationAttestationDistributionReady",
                mirrorRuntimeAvailability.attestationDistributionReady());
        features.put("mirrorIsolationRunTrustReady",
                mirrorRuntimeAvailability.certificationReady());
        features.put("mirrorCertifiableEvidenceServingReady",
                mirrorExecutionReady && mirrorRuntimeAvailability.certificationReady());
        features.put("mirrorObservationAdmissionApi",
                mirrorRuntimeAvailability.observationAdmissionApi());
        features.put("mirrorObservationAdmissionReady",
                mirrorRuntimeAvailability.observationAdmissionReady());
        features.put("mirrorCorpusGovernanceApi",
                mirrorRuntimeAvailability.corpusGovernanceApi());
        features.put("mirrorCorpusGovernanceReady",
                mirrorRuntimeAvailability.corpusGovernanceReady());
        features.put("mirrorCorpusTrajectoryPublicationApi",
                mirrorRuntimeAvailability.corpusTrajectoryApi());
        features.put("mirrorCorpusTrajectoryPublicationReady",
                mirrorRuntimeAvailability.corpusTrajectoryReady());
        features.put("mirrorCorpusClusterPublicationApi",
                mirrorRuntimeAvailability.corpusClusterApi());
        features.put("mirrorCorpusClusterPublicationReady",
                mirrorRuntimeAvailability.corpusClusterReady());
        features.put("mirrorCorpusResolverReady",
                mirrorRuntimeAvailability.corpusResolverReady());
        features.put("mirrorServingGenerationFencing",
                mirrorRuntimeAvailability.corpusResolverReady());
        features.put("mirrorServingGenerationAuthorityReady",
                mirrorRuntimeAvailability.corpusResolverReady());
        features.put("mirrorCorpusTrajectoryResolverReady",
                mirrorRuntimeAvailability.corpusTrajectoryResolverReady());
        features.put("mirrorCorpusClusterResolverReady",
                mirrorRuntimeAvailability.corpusClusterResolverReady());
        features.put("mirrorStatefulSessionApi", mirrorStatefulSessionApi);
        features.put("mirrorStatefulStateStoreReady", mirrorStatefulStoreReady);
        features.put("mirrorStateCheckpointProtocol",
                mirrorStatefulSessionApi);
        features.put("mirrorStateCheckpointApi",
                mirrorStatefulSessionApi);
        features.put("mirrorStateCheckpointReady",
                mirrorCheckpointReady);
        features.put("mirrorStateRecoveryReady",
                mirrorCheckpointReady);
        features.put("mirrorStatefulResolverReady", mirrorStatefulResolverReady);
        features.put("mirrorStateRunEvidenceReady",
                mirrorStatefulResolverReady);
        features.put("mirrorStateTransitionEvidenceReady",
                mirrorStatefulResolverReady);
        features.put("mirrorStateWriteOutcomeEvidenceReady",
                mirrorStatefulResolverReady);
        features.put("mirrorStateWorkbookSeedApi",
                mirrorExecutionApi);
        features.put("mirrorStateWorkbookSeedReady",
                mirrorStatefulResolverReady);
        features.put("mirrorStateTransitionWorkbookSeedApi",
                mirrorExecutionApi);
        features.put("mirrorStateTransitionWorkbookSeedReady",
                mirrorStatefulResolverReady);
        features.put("mirrorStateWriteOutcomeWorkbookSeedApi",
                mirrorExecutionApi);
        features.put("mirrorStateWriteOutcomeWorkbookSeedReady",
                mirrorStatefulResolverReady);
        features.put(
                "mirrorStateWriteAttemptDurableReconciliationReady",
                mirrorStatefulResolverReady
                        && mirrorWriteAttemptReconciliationReady);
        // Full crash/network/HA/DR certification remains a separate runtime gate.
        features.put("mirrorStatefulRuntimeReady", false);
        ExternalAnchorTrustState suiteAnchorTrust = currentSuiteStabilityAnchorTrust();
        features.put("managedSuiteStabilityExternalNotaryTrust",
                testExecutionEndpointEnabled && suiteAnchorTrust.managed());
        features.put("restartFreeSuiteStabilityExternalNotaryKeyRotation",
                testExecutionEndpointEnabled && suiteAnchorTrust.restartFreeRotation());
        features.put("durableSuiteStabilityExternalNotaryTrustFloor",
                testExecutionEndpointEnabled && suiteAnchorTrust.durableFloor());
        features.put("suiteStabilityExternalNotaryTrustReady",
                testExecutionEndpointEnabled && suiteAnchorTrust.ready());
        features.put("managedSuiteStabilityExternalNotaryBootstrapRoots",
                testExecutionEndpointEnabled && suiteAnchorTrust.managedBootstrapRoots());
        features.put("restartFreeSuiteStabilityExternalNotaryBootstrapRootRotation",
                testExecutionEndpointEnabled && suiteAnchorTrust.restartFreeBootstrapRootRotation());
        features.put("completeSuiteStabilityExternalNotaryBootstrapRootReplay",
                testExecutionEndpointEnabled && suiteAnchorTrust.completeBootstrapRootReplay());
        features.put("durableSuiteStabilityExternalNotaryBootstrapRootFloor",
                testExecutionEndpointEnabled && suiteAnchorTrust.durableBootstrapRootFloor());
        features.put("suiteStabilityExternalNotaryBootstrapRootsReady",
                testExecutionEndpointEnabled && suiteAnchorTrust.bootstrapRootsReady());
        features.put("suiteStabilityExternalNotaryTrustChainReady",
                testExecutionEndpointEnabled && suiteAnchorTrust.trustChainReady());
        ExternalAnchorTrustState secretAnchorTrust = currentTestSecretAnchorTrust();
        features.put("managedTestSecretExternalNotaryTrust",
                testExecutionEndpointEnabled && secretAnchorTrust.managed());
        features.put("restartFreeTestSecretExternalNotaryKeyRotation",
                testExecutionEndpointEnabled && secretAnchorTrust.restartFreeRotation());
        features.put("durableTestSecretExternalNotaryTrustFloor",
                testExecutionEndpointEnabled && secretAnchorTrust.durableFloor());
        features.put("testSecretExternalNotaryTrustReady",
                testExecutionEndpointEnabled && secretAnchorTrust.ready());
        features.put("managedTestSecretExternalNotaryBootstrapRoots",
                testExecutionEndpointEnabled && secretAnchorTrust.managedBootstrapRoots());
        features.put("restartFreeTestSecretExternalNotaryBootstrapRootRotation",
                testExecutionEndpointEnabled && secretAnchorTrust.restartFreeBootstrapRootRotation());
        features.put("completeTestSecretExternalNotaryBootstrapRootReplay",
                testExecutionEndpointEnabled && secretAnchorTrust.completeBootstrapRootReplay());
        features.put("durableTestSecretExternalNotaryBootstrapRootFloor",
                testExecutionEndpointEnabled && secretAnchorTrust.durableBootstrapRootFloor());
        features.put("testSecretExternalNotaryBootstrapRootsReady",
                testExecutionEndpointEnabled && secretAnchorTrust.bootstrapRootsReady());
        features.put("testSecretExternalNotaryTrustChainReady",
                testExecutionEndpointEnabled && secretAnchorTrust.trustChainReady());
        features.put("externalTestSecretAuthority",
                testExecutionEndpointEnabled && secretAuthorityReady);
        features.put("durableTestSecretReauthorization",
                testExecutionEndpointEnabled && secretAuthorityReady);
        boolean dynamicSecretTrust = testExecutionEndpointEnabled
                && "DYNAMIC_JWKS_ED25519".equals(
                testSecretAuthority.properties().get("trustProviderType"))
                && Boolean.TRUE.equals(testSecretAuthority.properties().get(
                "trustAutomaticRefresh"));
        Number secretRefreshInterval = testSecretAuthority.properties().get(
                "trustRefreshIntervalSeconds") instanceof Number value ? value : null;
        Number secretMaximumAge = testSecretAuthority.properties().get(
                "trustMaximumSnapshotAgeSeconds") instanceof Number value ? value : null;
        features.put("dynamicTestSecretAuthorityTrust", dynamicSecretTrust);
        features.put("testSecretAuthorityTrustRefreshSlo", dynamicSecretTrust
                && secretRefreshInterval != null && secretRefreshInterval.longValue() > 0
                && secretMaximumAge != null
                && secretMaximumAge.longValue() >= secretRefreshInterval.longValue()
                && Boolean.TRUE.equals(testSecretAuthority.properties().get(
                "trustConditionalRequests"))
                && Boolean.TRUE.equals(testSecretAuthority.properties().get(
                "trustFailClosedOnRefreshFailure")));
        Number secretExpectedReplicas = testSecretAuthority.properties().get(
                "trustCohortExpectedReplicaCount") instanceof Number value ? value : null;
        Number secretLiveReplicas = testSecretAuthority.properties().get(
                "trustCohortLiveReplicaCount") instanceof Number value ? value : null;
        Number secretHealthyReplicas = testSecretAuthority.properties().get(
                "trustCohortHealthyReplicaCount") instanceof Number value ? value : null;
        Number secretDistinctGenerations = testSecretAuthority.properties().get(
                "trustCohortDistinctGenerationCount") instanceof Number value ? value : null;
        Number secretDistinctInventoryGenerations = testSecretAuthority.properties().get(
                "trustCohortDistinctInventoryGenerationCount") instanceof Number value
                ? value : null;
        Number secretCohortLease = testSecretAuthority.properties().get(
                "trustCohortLeaseDurationSeconds") instanceof Number value ? value : null;
        boolean secretCohortSupported = dynamicSecretTrust
                && Boolean.TRUE.equals(testSecretAuthority.properties().get(
                "trustCohortConfigured"))
                && Boolean.TRUE.equals(testSecretAuthority.properties().get(
                "trustCohortDatabaseAuthority"))
                && Boolean.TRUE.equals(testSecretAuthority.properties().get(
                "trustCohortExactConfiguredInventory"))
                && secretExpectedReplicas != null && secretExpectedReplicas.longValue() > 0
                && secretCohortLease != null && secretCohortLease.longValue() > 0;
        boolean secretCohortReady = secretCohortSupported
                && Boolean.TRUE.equals(testSecretAuthority.properties().get(
                "trustCohortAvailable"))
                && "CONVERGED".equals(testSecretAuthority.properties().get(
                "trustCohortStatus"))
                && secretLiveReplicas != null && secretHealthyReplicas != null
                && secretDistinctGenerations != null
                && secretLiveReplicas.longValue() == secretExpectedReplicas.longValue()
                && secretHealthyReplicas.longValue() == secretExpectedReplicas.longValue()
                && secretDistinctGenerations.longValue() == 1;
        features.put("testSecretAuthorityTrustCohortConvergence", secretCohortSupported);
        features.put("testSecretAuthorityTrustCohortReady", secretCohortReady);
        boolean signedSecretInventory = secretCohortSupported
                && Boolean.TRUE.equals(testSecretAuthority.properties().get(
                "trustCohortExternallyAttestedInventory"));
        features.put("testSecretAuthorityDeploymentSignedInventory", signedSecretInventory);
        features.put("testSecretAuthorityDeploymentSignedInventoryReady",
                signedSecretInventory && secretCohortReady
                        && secretDistinctInventoryGenerations != null
                        && secretDistinctInventoryGenerations.longValue() == 1);
        boolean dynamicSecretInventory = signedSecretInventory
                && "DYNAMIC_HTTPS_SIGNED_PUBLICATION_WITH_WITNESS".equals(
                testSecretAuthority.properties().get("servingInventorySourceType"))
                && Boolean.TRUE.equals(testSecretAuthority.properties().get(
                "servingInventoryAutomaticRefresh"))
                && Boolean.TRUE.equals(testSecretAuthority.properties().get(
                "servingInventoryConditionalRequests"))
                && Boolean.TRUE.equals(testSecretAuthority.properties().get(
                "servingInventoryFailClosedOnRefreshFailure"));
        boolean signedSecretInventoryRevocation = dynamicSecretInventory
                && Boolean.TRUE.equals(testSecretAuthority.properties().get(
                "servingInventorySignedRevocation"));
        boolean witnessedSecretInventory = dynamicSecretInventory
                && Boolean.TRUE.equals(testSecretAuthority.properties().get(
                "servingInventoryWitnessedPublications"))
                && testSecretAuthority.properties().get(
                "servingInventoryWitnessSignatureThreshold") instanceof Number threshold
                && threshold.longValue() > 0;
        boolean durableSecretInventoryFloor = dynamicSecretInventory
                && Boolean.TRUE.equals(testSecretAuthority.properties().get(
                "servingInventoryDurablePublicationFloor"));
        boolean externallyAnchoredSecretInventoryFloor = dynamicSecretInventory
                && Boolean.TRUE.equals(testSecretAuthority.properties().get(
                "servingInventoryExternallyAnchoredPublicationFloor"));
        boolean byzantineSecretInventoryFloor = externallyAnchoredSecretInventoryFloor
                && Boolean.TRUE.equals(testSecretAuthority.properties().get(
                "servingInventoryByzantineQuorumPublicationFloor"));
        boolean managedSecretInventoryRoots = dynamicSecretInventory
                && Boolean.TRUE.equals(testSecretAuthority.properties().get(
                "servingInventoryManagedTrustRootRefresh"));
        boolean atomicSecretInventoryRoots = managedSecretInventoryRoots
                && Boolean.TRUE.equals(testSecretAuthority.properties().get(
                "servingInventoryAtomicDualTrustRootPublication"));
        boolean durableSecretInventoryRootFloor = managedSecretInventoryRoots
                && Boolean.TRUE.equals(testSecretAuthority.properties().get(
                "servingInventoryDurableTrustRootFloor"));
        boolean externallyAnchoredSecretInventoryRootFloor = managedSecretInventoryRoots
                && Boolean.TRUE.equals(testSecretAuthority.properties().get(
                "servingInventoryExternallyAnchoredTrustRootFloor"));
        boolean byzantineSecretInventoryRootFloor =
                externallyAnchoredSecretInventoryRootFloor
                && Boolean.TRUE.equals(testSecretAuthority.properties().get(
                "servingInventoryByzantineQuorumTrustRootFloor"));
        boolean dynamicSecretInventoryReady = dynamicSecretInventory
                && signedSecretInventoryRevocation && witnessedSecretInventory
                && durableSecretInventoryFloor && secretCohortReady
                && Boolean.TRUE.equals(testSecretAuthority.properties().get(
                "servingInventoryAvailable"))
                && "VERIFIED".equals(testSecretAuthority.properties().get(
                "servingInventoryStatus"));
        features.put("testSecretAuthorityDynamicServingInventory", dynamicSecretInventory);
        features.put("testSecretAuthoritySignedInventoryRevocation",
                signedSecretInventoryRevocation);
        features.put("testSecretAuthorityWitnessedInventoryPublication",
                witnessedSecretInventory);
        features.put("testSecretAuthorityDurableInventoryPublicationFloor",
                durableSecretInventoryFloor);
        features.put("testSecretAuthorityExternallyAnchoredInventoryPublicationFloor",
                externallyAnchoredSecretInventoryFloor);
        features.put("testSecretAuthorityByzantineQuorumInventoryPublicationFloor",
                byzantineSecretInventoryFloor);
        features.put("testSecretAuthorityManagedServingInventoryTrustRoots",
                managedSecretInventoryRoots);
        features.put("testSecretAuthorityAtomicDualServingInventoryTrustRoots",
                atomicSecretInventoryRoots);
        features.put("testSecretAuthorityDurableTrustRootFloor",
                durableSecretInventoryRootFloor);
        features.put("testSecretAuthorityExternallyAnchoredTrustRootFloor",
                externallyAnchoredSecretInventoryRootFloor);
        features.put("testSecretAuthorityByzantineQuorumTrustRootFloor",
                byzantineSecretInventoryRootFloor);
        features.put("testSecretAuthorityExternalNonEquivocationReady",
                dynamicSecretInventoryReady
                        && externallyAnchoredSecretInventoryFloor
                        && byzantineSecretInventoryFloor
                        && externallyAnchoredSecretInventoryRootFloor
                        && byzantineSecretInventoryRootFloor
                        && Boolean.TRUE.equals(testSecretAuthority.properties().get(
                        "servingInventoryExternalNonEquivocation")));
        features.put("testSecretAuthorityManagedTrustRootsReady",
                managedSecretInventoryRoots && atomicSecretInventoryRoots
                        && durableSecretInventoryRootFloor && dynamicSecretInventoryReady);
        features.put("testSecretAuthorityDynamicServingInventoryReady",
                dynamicSecretInventoryReady);
        ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability recoveryFleet =
                currentRecoveryFleetCapability();
        features.put("bootstrapRootRecoveryFleetConfigured", recoveryFleet.configured());
        features.put("bootstrapRootRecoveryFleetReady", recoveryFleet.ready());
        features.put("bootstrapRootRecoveryFleetExternallyAttested",
                recoveryFleet.externallyAttested());
        features.put("bootstrapRootRecoveryFleetDynamicInventory",
                recoveryFleet.dynamicInventory());
        features.put("bootstrapRootRecoveryFleetSignedRevocation",
                recoveryFleet.signedRevocation());
        features.put("bootstrapRootRecoveryFleetWitnessedPublications",
                recoveryFleet.witnessedPublications());
        features.put("bootstrapRootRecoveryFleetDurablePublicationFloor",
                recoveryFleet.durablePublicationFloor());
        features.put("bootstrapRootRecoveryFleetExternallyAnchoredPublicationFloor",
                recoveryFleet.externallyAnchoredPublicationFloor());
        features.put("bootstrapRootRecoveryFleetByzantineQuorumPublicationFloor",
                recoveryFleet.byzantineQuorumAnchoredPublicationFloor());
        features.put("bootstrapRootRecoveryFleetManagedTrustRoots",
                recoveryFleet.managedTrustRootRefresh());
        features.put("bootstrapRootRecoveryFleetManagedTrustRootsReady",
                recoveryFleet.managedTrustRootAvailable());
        features.put("bootstrapRootRecoveryFleetAtomicDualTrustRoots",
                recoveryFleet.atomicDualTrustRootPublication());
        features.put("bootstrapRootRecoveryFleetDurableTrustRootFloor",
                recoveryFleet.durableTrustRootFloor());
        features.put("bootstrapRootRecoveryFleetExternallyAnchoredTrustRootFloor",
                recoveryFleet.externallyAnchoredTrustRootFloor());
        features.put("bootstrapRootRecoveryFleetByzantineQuorumTrustRootFloor",
                recoveryFleet.byzantineQuorumAnchoredTrustRootFloor());
        features.put("bootstrapRootRecoveryFleetExternalInventoryNonEquivocation",
                recoveryFleet.externalInventoryNonEquivocation());
        features.put("bootstrapRootRecoveryFleetByzantineInventoryNonEquivocation",
                recoveryFleet.byzantineQuorumInventoryNonEquivocation());
        features.put("bootstrapRootRecoveryFleetInventorySourcePrivateTrust",
                recoveryFleet.inventorySourcePrivateTrustStore());
        features.put("bootstrapRootRecoveryFleetInventorySourceSpkiPinned",
                recoveryFleet.inventorySourceServerSpkiPinned());
        features.put("bootstrapRootRecoveryFleetInventorySourceMutualTls",
                recoveryFleet.inventorySourceMutualTls());
        features.put("bootstrapRootRecoveryFleetInventorySourceCertificateIdentityBound",
                recoveryFleet.inventorySourceCertificateIdentityBound());
        features.put("bootstrapRootRecoveryFleetTrustRootSourcePrivateTrust",
                recoveryFleet.trustRootSourcePrivateTrustStore());
        features.put("bootstrapRootRecoveryFleetTrustRootSourceSpkiPinned",
                recoveryFleet.trustRootSourceServerSpkiPinned());
        features.put("bootstrapRootRecoveryFleetTrustRootSourceMutualTls",
                recoveryFleet.trustRootSourceMutualTls());
        features.put("bootstrapRootRecoveryFleetTrustRootSourceCertificateIdentityBound",
                recoveryFleet.trustRootSourceCertificateIdentityBound());
        TestSuiteStabilityPhysicalAttemptRuntimeCapability physicalAttempt =
                currentPhysicalAttemptCapability();
        features.put("physicalAttemptRuntimeConfigured", physicalAttempt.configured());
        features.put("physicalAttemptRuntimeReady", physicalAttempt.ready());
        features.put("physicalAttemptProviderInventoryExternallyAttested",
                physicalAttempt.providerInventory().externallyAttested());
        features.put("physicalAttemptProviderInventoryAvailable",
                physicalAttempt.providerInventory().available());
        features.put("physicalAttemptProviderInventoryDynamic",
                physicalAttempt.dynamicInventory());
        features.put("physicalAttemptProviderInventoryAutomaticRefresh",
                physicalAttempt.automaticRefresh());
        features.put("physicalAttemptProviderInventorySignedRevocation",
                physicalAttempt.signedRevocation());
        features.put("physicalAttemptProviderInventoryWitnessedPublications",
                physicalAttempt.witnessedPublications());
        features.put("physicalAttemptProviderInventoryDurablePublicationFloor",
                physicalAttempt.durablePublicationFloor());
        features.put("physicalAttemptProviderInventoryExternalNonEquivocation",
                Boolean.TRUE.equals(physicalAttempt.providerInventory().properties()
                        .get("externalNonEquivocation")));
        features.put("physicalAttemptProviderInventoryByzantineNonEquivocation",
                Boolean.TRUE.equals(physicalAttempt.providerInventory().properties()
                        .get("byzantineQuorumNonEquivocation")));
        features.put("physicalAttemptProviderInventoryManagedTrustRootRefresh",
                physicalAttempt.managedTrustRootRefresh());
        features.put("physicalAttemptProviderInventoryManagedTrustRootAvailable",
                physicalAttempt.managedTrustRootAvailable());
        features.put("physicalAttemptProviderInventoryAtomicDualTrustRootPublication",
                physicalAttempt.atomicDualTrustRootPublication());
        features.put("physicalAttemptProviderInventoryDurableTrustRootFloor",
                physicalAttempt.durableTrustRootFloor());
        features.put("physicalAttemptProviderInventoryExternallyAnchoredTrustRootFloor",
                physicalAttempt.externallyAnchoredTrustRootFloor());
        features.put("physicalAttemptProviderInventoryByzantineTrustRootFloor",
                physicalAttempt.byzantineQuorumAnchoredTrustRootFloor());
        features.put("physicalAttemptProviderInventoryCohortConverged",
                physicalAttempt.cohortConverged());
        features.put("physicalAttemptObservationReconciliationReady",
                physicalAttempt.observationReconciliationReady());
        features.put("physicalAttemptTerminalProjectionReady",
                physicalAttempt.terminalProjectionReady());
        ControlPlaneCertificateRotationRuntime.Descriptor rotation =
                currentControlPlaneCertificateRotation();
        features.put("signedControlPlaneCertificateRotation", rotation.enabled());
        features.put("restartFreeControlPlaneCertificateRotation",
                rotation.enabled() && rotation.ready());
        features.put("controlPlaneCertificateRotationLocalReady",
                rotation.enabled() && rotation.ready());
        features.put("controlPlaneCertificateRotationDurableFloorIntegrated",
                rotation.enabled() && rotation.durableState());
        features.put("controlPlaneCertificateRotationReplicaConvergenceIntegrated",
                rotation.convergenceIntegrated());
        features.put("controlPlaneCertificateRotationReplicaConvergenceAvailable",
                rotation.convergenceAvailable());
        features.put("controlPlaneCertificateRotationReplicaConvergenceProven",
                rotation.replicaConvergenceProven());
        features.put("controlPlaneCertificateRotationServingReady",
                rotation.servingReady());
        features.put("controlPlaneCertificateStatusIntegrated",
                rotation.certificateStatusIntegrated());
        features.put("controlPlaneCertificateStatusAvailable",
                rotation.certificateStatusAvailable());
        features.put("controlPlaneCertificateStatusFresh",
                rotation.certificateStatusFresh());
        features.put("controlPlaneCertificateRevocationAdmission",
                rotation.certificateStatusIntegrated() && rotation.certificateStatusFresh());
        CertificateStatusSloCapability statusSlo = currentCertificateStatusSlo();
        features.put("controlPlaneCertificateStatusSloIntegrated", statusSlo.integrated());
        features.put("controlPlaneCertificateStatusSloHealthy", statusSlo.healthy());
        features.put("controlPlaneCertificateStatusExactSourceHead",
                statusSlo.exactSourceHead());
        features.put("controlPlaneCertificateStatusFixedCardinalityTelemetry",
                statusSlo.integrated());
        CertificateRotationEventDeliveryCapability eventDelivery =
                currentCertificateRotationEventDelivery();
        features.put("controlPlaneCertificateRotationEventDeliveryIntegrated",
                eventDelivery.integrated());
        features.put("controlPlaneCertificateRotationEventDeliveryReady",
                eventDelivery.ready());
        features.put("controlPlaneCertificateRotationEventDeliveryDurableCursor",
                eventDelivery.durableCursor());
        features.put("controlPlaneCertificateRotationEventDeliveryAuthenticatedSource",
                eventDelivery.authenticatedSource());
        features.put("controlPlaneCertificateRotationEventDeliverySourceMutualTls",
                eventDelivery.sourceMutualTls());
        features.put("controlPlaneCertificateRotationEventDeliverySourceCertificateIdentityBound",
                eventDelivery.sourceCertificateIdentityBound());
        features.put("controlPlaneCertificateRotationProductionReady",
                rotation.productionReady());
        Map<String, List<String>> supportedObjects = new LinkedHashMap<>(current.supportedObjects());
        if (mirrorPlanReady) {
            supportedObjects.put("mirrorPlanCreateRequest", List.of(
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorPlanCreateRequest.SCHEMA_VERSION));
            supportedObjects.put("caseHandlingAssertion", List.of(
                    com.leanowtech.bloge.gateway.integration.mirror
                            .CaseHandlingAssertion.SCHEMA_VERSION));
            supportedObjects.put("scenarioCase", List.of(
                    com.leanowtech.bloge.gateway.integration.mirror
                            .ScenarioCase.SCHEMA_VERSION));
            supportedObjects.put("scenarioPack", List.of(
                    com.leanowtech.bloge.gateway.integration.mirror
                            .ScenarioPack.SCHEMA_VERSION));
            supportedObjects.put("scenarioRehearsalCompileRequest", List.of(
                    com.leanowtech.bloge.gateway.integration.mirror
                            .ScenarioRehearsalCompileRequest.SCHEMA_VERSION));
            supportedObjects.put("compiledScenarioRehearsalPlan", List.of(
                    com.leanowtech.bloge.gateway.integration.mirror
                            .CompiledScenarioRehearsalPlan.SCHEMA_VERSION));
            supportedObjects.put("scenarioRehearsalExecutionRequest", List.of(
                    com.leanowtech.bloge.gateway.integration.mirror
                            .ScenarioRehearsalExecutionRequest.SCHEMA_VERSION));
            supportedObjects.put("scenarioCaseRehearsalResult", List.of(
                    com.leanowtech.bloge.gateway.integration.mirror
                            .ScenarioCaseRehearsalResult.SCHEMA_VERSION));
            supportedObjects.put("scenarioRehearsalResult", List.of(
                    com.leanowtech.bloge.gateway.integration.mirror
                            .ScenarioRehearsalResult.SCHEMA_VERSION));
            supportedObjects.put(
                    "scenarioRehearsalEvidenceAttestation",
                    List.of(
                            com.leanowtech.bloge.gateway.integration.mirror
                                    .ScenarioRehearsalEvidenceAttestation
                                    .SCHEMA_VERSION));
            supportedObjects.put(
                    "scenarioRehearsalEvidenceBundle",
                    List.of(
                            com.leanowtech.bloge.gateway.integration.mirror
                                    .ScenarioRehearsalEvidenceBundle
                                    .SCHEMA_VERSION));
            supportedObjects.put(
                    "scenarioRehearsalLegalHoldCommand",
                    List.of(
                            com.leanowtech.bloge.gateway.integration.mirror
                                    .ScenarioRehearsalLegalHoldCommand
                                    .SCHEMA_VERSION));
            supportedObjects.put(
                    "scenarioRehearsalPurgeCommand",
                    List.of(
                            com.leanowtech.bloge.gateway.integration.mirror
                                    .ScenarioRehearsalPurgeCommand
                                    .SCHEMA_VERSION));
            supportedObjects.put(
                    "scenarioRehearsalRetentionEvent",
                    List.of(
                            com.leanowtech.bloge.gateway.integration.mirror
                                    .ScenarioRehearsalRetentionEvent
                                    .SCHEMA_VERSION));
            supportedObjects.put(
                    "scenarioRehearsalRetentionState",
                    List.of(
                            com.leanowtech.bloge.gateway.integration.mirror
                                    .ScenarioRehearsalRetentionState
                                    .SCHEMA_VERSION));
            supportedObjects.put(
                    "scenarioRehearsalWorkbookSeed",
                    List.of(
                            com.leanowtech.bloge.gateway.integration.mirror
                                    .ScenarioRehearsalWorkbookSeed
                                    .SCHEMA_VERSION));
            if (mirrorExecutionApi) {
                supportedObjects.put(
                        "scenarioRehearsalBatchRequest",
                        List.of(
                                com.leanowtech.bloge.gateway.integration.mirror
                                        .ScenarioRehearsalBatchRequest
                                        .SCHEMA_VERSION));
                supportedObjects.put(
                        "scenarioRehearsalBatchManifest",
                        List.of(
                                com.leanowtech.bloge.gateway.integration.mirror
                                        .ScenarioRehearsalBatchManifest
                                        .SCHEMA_VERSION));
                supportedObjects.put(
                        "scenarioRehearsalBatchJob",
                        List.of(
                                com.leanowtech.bloge.gateway.integration.mirror
                                        .ScenarioRehearsalBatchJob
                                        .SCHEMA_VERSION));
                supportedObjects.put(
                        "scenarioRehearsalBatchItemPage",
                        List.of(
                                com.leanowtech.bloge.gateway.integration.mirror
                                        .ScenarioRehearsalBatchItemPage
                                        .SCHEMA_VERSION));
                supportedObjects.put(
                        "scenarioRehearsalBatchCancellationRequest",
                        List.of(
                                com.leanowtech.bloge.gateway.integration.mirror
                                        .ScenarioRehearsalBatchCancellationRequest
                                        .SCHEMA_VERSION));
                supportedObjects.put(
                        "scenarioRehearsalBatchEvidenceIndex",
                        List.of(
                                com.leanowtech.bloge.gateway.integration.mirror
                                        .ScenarioRehearsalBatchEvidenceIndex
                                        .SCHEMA_VERSION));
                supportedObjects.put(
                        "scenarioRehearsalBatchEvidenceAttestation",
                        List.of(
                                com.leanowtech.bloge.gateway.integration.mirror
                                        .ScenarioRehearsalBatchEvidenceAttestation
                                        .SCHEMA_VERSION));
                supportedObjects.put(
                        "scenarioRehearsalBatchEvidenceBundle",
                        List.of(
                                com.leanowtech.bloge.gateway.integration.mirror
                                        .ScenarioRehearsalBatchEvidenceBundle
                                        .SCHEMA_VERSION));
                supportedObjects.put(
                        "scenarioRehearsalBatchRetentionEvent",
                        List.of(
                                com.leanowtech.bloge.gateway.integration.mirror
                                        .ScenarioRehearsalBatchRetentionEvent
                                        .SCHEMA_VERSION));
                supportedObjects.put(
                        "scenarioRehearsalBatchRetentionState",
                        List.of(
                                com.leanowtech.bloge.gateway.integration.mirror
                                        .ScenarioRehearsalBatchRetentionState
                                        .SCHEMA_VERSION));
            }
        }
        if (mirrorExecutionApi) {
            supportedObjects.put("mirrorExecutionRequest", List.of(
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorExecutionRequest.SCHEMA_VERSION,
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorExecutionRequest.STATEFUL_SCHEMA_VERSION));
            supportedObjects.put("mirrorRunSummary", List.of(
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorRunSummary.SCHEMA_VERSION));
            supportedObjects.put("mirrorEvidenceBundle", List.of(
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorEvidenceBundle.SCHEMA_VERSION_V1,
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorEvidenceBundle.SCHEMA_VERSION,
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorEvidenceBundle.STATEFUL_SCHEMA_VERSION,
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorEvidenceBundle.READ_WRITE_SCHEMA_VERSION,
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorEvidenceBundle.WRITE_OUTCOME_SCHEMA_VERSION));
            supportedObjects.put("mirrorRunEvidence", List.of(
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorRunEvidence.SCHEMA_VERSION_V1,
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorRunEvidence.SCHEMA_VERSION,
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorRunEvidence.STATEFUL_SCHEMA_VERSION,
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorRunEvidence.READ_WRITE_SCHEMA_VERSION,
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorRunEvidence.WRITE_OUTCOME_SCHEMA_VERSION));
            supportedObjects.put("mirrorEvidenceAttestation", List.of(
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorEvidenceAttestation.SCHEMA_VERSION_V1,
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorEvidenceAttestation.SCHEMA_VERSION,
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorEvidenceAttestation.STATEFUL_SCHEMA_VERSION,
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorEvidenceAttestation.READ_WRITE_SCHEMA_VERSION,
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorEvidenceAttestation
                            .WRITE_OUTCOME_SCHEMA_VERSION));
            supportedObjects.put("mirrorStateRunEvidence", List.of(
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorStateRunEvidence.SCHEMA_VERSION,
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorStateTransitionRunEvidence.SCHEMA_VERSION,
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorStateWriteOutcomeRunEvidence
                            .SCHEMA_VERSION));
            supportedObjects.put("mirrorStateWorkbookSeed", List.of(
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorStateWorkbookSeed.SCHEMA_VERSION));
            supportedObjects.put(
                    "mirrorStateTransitionWorkbookSeed", List.of(
                            com.leanowtech.bloge.gateway.integration.mirror
                                    .MirrorStateTransitionWorkbookSeed
                                    .SCHEMA_VERSION));
            supportedObjects.put(
                    "mirrorStateWriteOutcomeWorkbookSeed", List.of(
                            com.leanowtech.bloge.gateway.integration.mirror
                                    .MirrorStateWriteOutcomeWorkbookSeed
                                    .SCHEMA_VERSION));
        }
        if (mirrorStatefulSessionApi) {
            supportedObjects.put("stateReadSpec", List.of(
                    com.leanowtech.bloge.gateway.integration.mirror
                            .StateReadSpec.SCHEMA_VERSION));
            supportedObjects.put("mirrorSessionPayload", List.of(
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorSessionPayload.SCHEMA_VERSION));
            supportedObjects.put("mirrorSessionCreateRequest", List.of(
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorSessionCreateRequest.SCHEMA_VERSION));
            supportedObjects.put("mirrorSessionDescriptor", List.of(
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorSessionDescriptor.SCHEMA_VERSION));
            supportedObjects.put("mirrorSessionCommandRequest", List.of(
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorSessionCommandRequest.SCHEMA_VERSION));
            supportedObjects.put("mirrorSessionCommandResult", List.of(
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorSessionCommandResult.SCHEMA_VERSION));
            supportedObjects.put("mirrorStateWriteAttempt", List.of(
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorStateWriteAttempt.SCHEMA_VERSION));
            supportedObjects.put("mirrorSessionStoreGeneration", List.of(
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorSessionStoreGeneration.SCHEMA_VERSION));
            supportedObjects.put("mirrorSessionCheckpoint", List.of(
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorSessionCheckpoint.SCHEMA_VERSION));
            supportedObjects.put("mirrorSessionCheckpointAttestation", List.of(
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorSessionCheckpointAttestation.SCHEMA_VERSION));
            supportedObjects.put("mirrorSessionCheckpointBundle", List.of(
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorSessionCheckpointBundle.SCHEMA_VERSION));
            supportedObjects.put("mirrorSessionRecoveryResult", List.of(
                    com.leanowtech.bloge.gateway.integration.mirror
                            .MirrorSessionRecoveryResult.SCHEMA_VERSION));
        }
        List<IntegrationCapabilities.Endpoint> endpoints =
                new java.util.ArrayList<>(current.endpoints());
        if (mirrorPlanReady) {
            endpoints.add(new IntegrationCapabilities.Endpoint("POST", "/api/mirror/plans"));
            endpoints.add(new IntegrationCapabilities.Endpoint("GET", "/api/mirror/plans/{planId}"));
            endpoints.add(new IntegrationCapabilities.Endpoint(
                    "POST", "/api/mirror/scenarios/assertions"));
            endpoints.add(new IntegrationCapabilities.Endpoint(
                    "POST", "/api/mirror/scenarios/checkpoints"));
            endpoints.add(new IntegrationCapabilities.Endpoint(
                    "POST", "/api/mirror/scenarios/cases"));
            endpoints.add(new IntegrationCapabilities.Endpoint(
                    "POST", "/api/mirror/scenarios/packs"));
            endpoints.add(new IntegrationCapabilities.Endpoint(
                    "GET", "/api/mirror/scenarios/packs/{packId}"));
            endpoints.add(new IntegrationCapabilities.Endpoint(
                    "POST",
                    "/api/mirror/scenarios/packs/{packId}/compiled-plans"));
            endpoints.add(new IntegrationCapabilities.Endpoint(
                    "GET",
                    "/api/mirror/scenarios/compiled-plans/{planId}"));
            if (mirrorExecutionApi) {
                endpoints.add(new IntegrationCapabilities.Endpoint(
                        "POST", "/api/mirror/scenarios/runs"));
                endpoints.add(new IntegrationCapabilities.Endpoint(
                        "GET",
                        "/api/mirror/scenarios/runs/{runId}/evidence"));
                endpoints.add(new IntegrationCapabilities.Endpoint(
                        "GET",
                        "/api/mirror/scenarios/runs/{runId}/workbook-seed"));
                endpoints.add(new IntegrationCapabilities.Endpoint(
                        "GET",
                        "/api/mirror/scenarios/runs/{runId}/retention"));
                endpoints.add(new IntegrationCapabilities.Endpoint(
                        "POST",
                        "/api/mirror/scenarios/runs/{runId}/retention/holds"));
                endpoints.add(new IntegrationCapabilities.Endpoint(
                        "POST",
                        "/api/mirror/scenarios/runs/{runId}/retention/hold-releases"));
                endpoints.add(new IntegrationCapabilities.Endpoint(
                        "POST",
                        "/api/mirror/scenarios/runs/{runId}/retention/purge"));
                endpoints.add(new IntegrationCapabilities.Endpoint(
                        "POST", "/api/mirror/rehearsal-jobs"));
                endpoints.add(new IntegrationCapabilities.Endpoint(
                        "GET", "/api/mirror/rehearsal-jobs/{jobId}"));
                endpoints.add(new IntegrationCapabilities.Endpoint(
                        "GET", "/api/mirror/rehearsal-jobs/{jobId}/items"));
                endpoints.add(new IntegrationCapabilities.Endpoint(
                        "GET", "/api/mirror/rehearsal-jobs/{jobId}/evidence"));
                endpoints.add(new IntegrationCapabilities.Endpoint(
                        "POST",
                        "/api/mirror/rehearsal-jobs/{jobId}/cancellations"));
                endpoints.add(new IntegrationCapabilities.Endpoint(
                        "GET",
                        "/api/mirror/rehearsal-jobs/{jobId}/retention"));
                endpoints.add(new IntegrationCapabilities.Endpoint(
                        "POST",
                        "/api/mirror/rehearsal-jobs/{jobId}/retention/holds"));
                endpoints.add(new IntegrationCapabilities.Endpoint(
                        "POST",
                        "/api/mirror/rehearsal-jobs/{jobId}/retention/hold-releases"));
                endpoints.add(new IntegrationCapabilities.Endpoint(
                        "POST",
                        "/api/mirror/rehearsal-jobs/{jobId}/retention/purge"));
            }
        }
        if (mirrorExecutionApi) {
            endpoints.add(new IntegrationCapabilities.Endpoint("POST", "/api/mirror/executions"));
            endpoints.add(new IntegrationCapabilities.Endpoint("GET", "/api/mirror/runs/{runId}"));
            endpoints.add(new IntegrationCapabilities.Endpoint(
                    "GET", "/api/mirror/runs/{runId}/evidence"));
            endpoints.add(new IntegrationCapabilities.Endpoint(
                    "GET",
                    "/api/mirror/runs/{runId}/state-workbook-seed"));
            endpoints.add(new IntegrationCapabilities.Endpoint(
                    "GET",
                    "/api/mirror/runs/{runId}/state-transition-workbook-seed"));
            endpoints.add(new IntegrationCapabilities.Endpoint(
                    "GET",
                    "/api/mirror/runs/{runId}/state-write-outcome-workbook-seed"));
        }
        if (mirrorStatefulSessionApi) {
            endpoints.add(new IntegrationCapabilities.Endpoint(
                    "POST", "/api/mirror/sessions"));
            endpoints.add(new IntegrationCapabilities.Endpoint(
                    "GET", "/api/mirror/sessions/{sessionId}"));
            endpoints.add(new IntegrationCapabilities.Endpoint(
                    "POST", "/api/mirror/sessions/{sessionId}/commands"));
            endpoints.add(new IntegrationCapabilities.Endpoint(
                    "GET",
                    "/api/mirror/sessions/{sessionId}/write-attempts/{attemptId}"));
            endpoints.add(new IntegrationCapabilities.Endpoint(
                    "POST", "/api/mirror/sessions/{sessionId}/checkpoints"));
            endpoints.add(new IntegrationCapabilities.Endpoint(
                    "POST", "/api/mirror/sessions/{sessionId}/recoveries"));
            endpoints.add(new IntegrationCapabilities.Endpoint(
                    "DELETE", "/api/mirror/sessions/{sessionId}"));
        }
        if (mirrorRuntimeAvailability.authorityDistributionApi()) {
            endpoints.add(new IntegrationCapabilities.Endpoint("POST",
                    "/api/mirror/trust/deployment-isolation/authority-key-sets"));
            endpoints.add(new IntegrationCapabilities.Endpoint("GET",
                    "/api/mirror/trust/deployment-isolation/authority-key-sets/{keySetId}/latest"));
            endpoints.add(new IntegrationCapabilities.Endpoint("GET",
                    "/api/mirror/trust/deployment-isolation/authority-key-sets/{keySetId}/generations/{generation}"));
        }
        if (mirrorRuntimeAvailability.attestationDistributionApi()) {
            endpoints.add(new IntegrationCapabilities.Endpoint("POST",
                    "/api/mirror/trust/deployment-isolation/attestations"));
            endpoints.add(new IntegrationCapabilities.Endpoint("GET",
                    "/api/mirror/trust/deployment-isolation/attestations/{attestationId}/latest"));
            endpoints.add(new IntegrationCapabilities.Endpoint("GET",
                    "/api/mirror/trust/deployment-isolation/attestations/{attestationId}/revisions/{revision}"));
            endpoints.add(new IntegrationCapabilities.Endpoint("POST",
                    "/api/mirror/trust/deployment-isolation/attestations/{attestationId}/revocations"));
        }
        if (mirrorRuntimeAvailability.observationAdmissionApi()) {
            endpoints.add(new IntegrationCapabilities.Endpoint(
                    "POST", "/api/mirror/observations"));
        }
        if (mirrorRuntimeAvailability.corpusGovernanceApi()) {
            endpoints.add(new IntegrationCapabilities.Endpoint(
                    "POST", "/api/mirror/observations/{observationId}/reviews"));
            endpoints.add(new IntegrationCapabilities.Endpoint(
                    "POST", "/api/mirror/corpus-candidates"));
            endpoints.add(new IntegrationCapabilities.Endpoint(
                    "POST", "/api/mirror/corpus-publications"));
        }
        if (mirrorRuntimeAvailability.corpusTrajectoryApi()) {
            endpoints.add(new IntegrationCapabilities.Endpoint(
                    "POST", "/api/mirror/corpus-trajectories"));
        }
        if (mirrorRuntimeAvailability.corpusClusterApi()) {
            endpoints.add(new IntegrationCapabilities.Endpoint(
                    "POST", "/api/mirror/corpus-clusters"));
        }
        IntegrationCapabilities augmented = new IntegrationCapabilities(
                current.schemaVersion(), current.protocol(), current.protocolVersion(),
                supportedObjects, features, current.identityProvider(),
                current.evidenceSigner(), current.payloadGovernance(),
                current.testability().withRecoveryFleet(recoveryFleet)
                        .withPhysicalAttemptRuntime(physicalAttempt),
                endpoints);
        return IntegrationEnvelope.of("CAPABILITIES", IntegrationCapabilities.SCHEMA_VERSION,
                augmented);
    }

    private ControlPlaneCertificateRotationRuntime.Descriptor
            currentControlPlaneCertificateRotation() {
        if (controlPlaneCertificateRotationRuntime == null) {
            return new ControlPlaneCertificateRotationRuntime.Descriptor(
                    ControlPlaneCertificateRotationRuntime.Descriptor.SCHEMA_VERSION,
                    false, true, false, false, 0, 0, true,
                    false, false, false, false, false, "DISABLED");
        }
        try {
            return controlPlaneCertificateRotationRuntime.descriptor();
        } catch (RuntimeException unavailable) {
            return new ControlPlaneCertificateRotationRuntime.Descriptor(
                    ControlPlaneCertificateRotationRuntime.Descriptor.SCHEMA_VERSION,
                    true, false, false, false, 0, 0, false,
                    false, false, false, false, false, "UNAVAILABLE");
        }
    }

    private CertificateRotationEventDeliveryCapability
            currentCertificateRotationEventDelivery() {
        if (controlPlaneCertificateRotationEventWatcher == null) {
            return CertificateRotationEventDeliveryCapability.disabled();
        }
        try {
            ControlPlaneCertificateRotationEventWatcher.Descriptor current =
                    controlPlaneCertificateRotationEventWatcher.descriptor();
            return new CertificateRotationEventDeliveryCapability(
                    true, current.ready(), current.durableCursor(),
                    current.authenticatedProtocol(), current.sourceMutualTls(),
                    current.sourceCertificateIdentityBound());
        } catch (RuntimeException unavailable) {
            return CertificateRotationEventDeliveryCapability.unavailable();
        }
    }

    private CertificateStatusSloCapability currentCertificateStatusSlo() {
        if (controlPlaneCertificateStatusSloMonitor == null) {
            return new CertificateStatusSloCapability(false, false, false);
        }
        try {
            ControlPlaneCertificateStatusSloMonitor.Assessment assessment =
                    controlPlaneCertificateStatusSloMonitor.assess();
            return new CertificateStatusSloCapability(true,
                    assessment.state() == ControlPlaneCertificateStatusSloMonitor.State.HEALTHY,
                    assessment.sourceHeadVerified());
        } catch (RuntimeException unavailable) {
            return new CertificateStatusSloCapability(true, false, false);
        }
    }

    private record CertificateStatusSloCapability(
            boolean integrated, boolean healthy, boolean exactSourceHead) {
    }

    private record CertificateRotationEventDeliveryCapability(
            boolean integrated,
            boolean ready,
            boolean durableCursor,
            boolean authenticatedSource,
            boolean sourceMutualTls,
            boolean sourceCertificateIdentityBound) {

        private static CertificateRotationEventDeliveryCapability disabled() {
            return new CertificateRotationEventDeliveryCapability(
                    false, false, false, false, false, false);
        }

        private static CertificateRotationEventDeliveryCapability unavailable() {
            return new CertificateRotationEventDeliveryCapability(
                    true, false, false, false, false, false);
        }
    }

    private ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability
            currentRecoveryFleetCapability() {
        try {
            int inventories = recoveryFleetInventories.size();
            int authorities = recoveryFleetAuthorities.size();
            int workers = recoveryFleetWorkers.size();
            int schedulers = recoveryFleetSchedulers.size();
            if (inventories == 0 && authorities == 0 && workers == 0 && schedulers == 0) {
                return ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.disabled();
            }
            if (inventories > 1 || authorities > 1 || workers > 1 || schedulers > 1) {
                return ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.ambiguous();
            }
            if (inventories != 1 || workers != 1 || schedulers != 1) {
                return ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.incomplete();
            }
            if (authorities == 0) {
                return ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.unattested();
            }
            if (recoveryFleetInventories.getFirst() != recoveryFleetAuthorities.getFirst()) {
                return ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.ambiguous();
            }
            return ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.project(
                    recoveryFleetAuthorities.getFirst(), recoveryFleetWorkers.getFirst(),
                    recoveryFleetSchedulers.getFirst());
        } catch (RuntimeException unavailable) {
            return ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.unavailable();
        }
    }

    private TestSuiteStabilityPhysicalAttemptRuntimeCapability
            currentPhysicalAttemptCapability() {
        try {
            int inventories = physicalAttemptProviderInventories.size();
            int cohorts = physicalAttemptInventoryCohorts.size();
            int reconciliation = physicalAttemptReconciliationHealth.size();
            int terminal = physicalAttemptTerminalHealth.size();
            if (inventories == 0 && cohorts == 0 && reconciliation == 0 && terminal == 0) {
                return TestSuiteStabilityPhysicalAttemptRuntimeCapability.disabled();
            }
            if (inventories > 1 || cohorts > 1 || reconciliation > 1 || terminal > 1) {
                return TestSuiteStabilityPhysicalAttemptRuntimeCapability.ambiguous();
            }
            if (inventories != 1 || cohorts != 1 || reconciliation != 1 || terminal != 1) {
                return TestSuiteStabilityPhysicalAttemptRuntimeCapability.incomplete();
            }
            return TestSuiteStabilityPhysicalAttemptRuntimeCapability.project(
                    physicalAttemptProviderInventories.getFirst(),
                    physicalAttemptInventoryCohorts.getFirst(),
                    physicalAttemptReconciliationHealth.getFirst(),
                    physicalAttemptTerminalHealth.getFirst());
        } catch (RuntimeException unavailable) {
            return TestSuiteStabilityPhysicalAttemptRuntimeCapability.unavailable();
        }
    }

    private TestSecretAuthority.Descriptor currentTestSecretAuthority() {
        if (testSecretAuthorities == null) {
            return TestSecretAuthority.unavailable().descriptor();
        }
        try {
            TestSecretAuthority authority = testSecretAuthorities.getIfAvailable();
            return authority == null
                    ? TestSecretAuthority.unavailable().descriptor() : authority.descriptor();
        } catch (RuntimeException unavailable) {
            return TestSecretAuthority.unavailable().descriptor();
        }
    }

    private ExternalAnchorTrustState currentSuiteStabilityAnchorTrust() {
        if (suiteStabilityExternalSequenceAnchors == null) {
            return ExternalAnchorTrustState.unavailable();
        }
        try {
            List<TestSuiteStabilityExternalSequenceAnchor> anchors =
                    suiteStabilityExternalSequenceAnchors.orderedStream().toList();
            if (anchors.size() != 1) {
                return ExternalAnchorTrustState.unavailable();
            }
            TestSuiteStabilityExternalSequenceAnchor anchor = anchors.getFirst();
            return ExternalAnchorTrustState.from(anchor.descriptor(), anchor.trustSnapshot(),
                    anchor.bootstrapRootDescriptor(), anchor.bootstrapRootSnapshot());
        } catch (RuntimeException unavailable) {
            return ExternalAnchorTrustState.unavailable();
        }
    }

    private ExternalAnchorTrustState currentTestSecretAnchorTrust() {
        if (testSecretAuthorityExternalSequenceAnchors == null) {
            return ExternalAnchorTrustState.unavailable();
        }
        try {
            List<TestSecretAuthorityExternalSequenceAnchor> anchors =
                    testSecretAuthorityExternalSequenceAnchors.orderedStream().toList();
            if (anchors.size() != 1) {
                return ExternalAnchorTrustState.unavailable();
            }
            TestSecretAuthorityExternalSequenceAnchor anchor = anchors.getFirst();
            return ExternalAnchorTrustState.from(anchor.descriptor(), anchor.trustSnapshot(),
                    anchor.bootstrapRootDescriptor(), anchor.bootstrapRootSnapshot());
        } catch (RuntimeException unavailable) {
            return ExternalAnchorTrustState.unavailable();
        }
    }

    private record ExternalAnchorTrustState(
            boolean managed,
            boolean restartFreeRotation,
            boolean durableFloor,
            boolean ready,
            boolean managedBootstrapRoots,
            boolean restartFreeBootstrapRootRotation,
            boolean completeBootstrapRootReplay,
            boolean durableBootstrapRootFloor,
            boolean bootstrapRootsReady,
            boolean trustChainReady) {

        private static ExternalAnchorTrustState from(
                TestSuiteStabilityExternalSequenceAnchor.Descriptor descriptor,
                ExternalSequenceAnchorReceiptTrustStore.Snapshot snapshot,
                ExternalSequenceAnchorBootstrapRootTrustStore.Descriptor rootDescriptor,
                ExternalSequenceAnchorBootstrapRootTrustStore.Snapshot rootSnapshot) {
            boolean managed = Boolean.TRUE.equals(
                    descriptor.properties().get("managedTrustPublication"));
            boolean restartFree = Boolean.TRUE.equals(
                    descriptor.properties().get("restartFreeNotaryKeyRotation"));
            boolean durable = Boolean.TRUE.equals(
                    descriptor.properties().get("durableTrustPublicationFloor"));
            boolean managedRoots = rootDescriptor.managedChain();
            boolean restartFreeRoots = rootDescriptor.restartFreeRotation();
            boolean completeReplay = rootDescriptor.completeGenesisReplay();
            boolean durableRootFloor = rootDescriptor.durableFloor();
            boolean rootsReady = managedRoots && restartFreeRoots && completeReplay
                    && durableRootFloor && rootDescriptor.available() && rootSnapshot.available();
            boolean notaryReady = managed && restartFree && durable
                    && descriptor.available() && snapshot.available();
            return new ExternalAnchorTrustState(managed, restartFree, durable, notaryReady,
                    managedRoots, restartFreeRoots, completeReplay, durableRootFloor, rootsReady,
                    notaryReady && rootsReady);
        }

        private static ExternalAnchorTrustState unavailable() {
            return new ExternalAnchorTrustState(false, false, false, false,
                    false, false, false, false, false, false);
        }
    }

    private TestSuiteStabilityObservationExternalArchiveReconciliationHealth.Descriptor
            currentExternalArchiveReconciliation() {
        if (externalArchiveReconciliationHealth == null) {
            return TestSuiteStabilityObservationExternalArchiveReconciliationHealth.Descriptor
                    .unavailable();
        }
        try {
            return externalArchiveReconciliationHealth.descriptor();
        } catch (RuntimeException unavailable) {
            return new TestSuiteStabilityObservationExternalArchiveReconciliationHealth.Descriptor(
                    "", true, false, "STORE_UNAVAILABLE",
                    List.of("RECONCILIATION_STORE_UNAVAILABLE"), Instant.now(), 0);
        }
    }

    private TestSuiteStabilityJobAuthorizer.Descriptor currentSuiteStabilityAuthority() {
        if (suiteStabilityAuthorizers == null) {
            return suiteStabilityCurrentAuthority;
        }
        try {
            List<TestSuiteStabilityJobAuthorizer> providers =
                    suiteStabilityAuthorizers.orderedStream().toList();
            if (providers.size() != 1) {
                return unavailableCurrentAuthority();
            }
            return providers.getFirst().descriptor();
        } catch (RuntimeException unavailable) {
            return unavailableCurrentAuthority();
        }
    }

    private static TestSuiteStabilityJobAuthorizer.Descriptor unavailableCurrentAuthority() {
        return new TestSuiteStabilityJobAuthorizer.Descriptor(
                "", false, "UNAVAILABLE", "", Map.of());
    }

    public IntegrationEnvelope<GraphDraftIntegrationBundle> exportDraft(String draftId,
                                                                        long revision,
                                                                        IntegrationRequestContext context) {
        context.requireComplete();
        GraphDraft draft = findDraft(draftId, revision, context);
        context.requireDraftScope(draft);
        GraphDraftDependencySnapshotService.Snapshot dependencySnapshot = dependencySnapshots.capture(draft);
        GraphDraftDependencyReport dependencyReport = GraphDraftDependencyReport.from(
                draft, dependencySnapshot.catalog());
        VisualValidationResult validation = validator.validate(draft);
        String draftFingerprint = draftFingerprint(draft);
        GraphDraftIntegrationBundle bundle = new GraphDraftIntegrationBundle(
                "", context.tenantId(), context.organizationId(), context.projectId(), context.environmentId(),
                draftFingerprint, draft, dependencySnapshot.operators(),
                GraphDraftDependencyProfile.from(draft, dependencyReport, dependencySnapshot), validation
        );
        verifySnapshotStable(draft, revision, dependencySnapshot, context);
        return IntegrationEnvelope.of("GRAPH_DRAFT_INTEGRATION_BUNDLE",
                GraphDraftIntegrationBundle.SCHEMA_VERSION, bundle);
    }

    public IntegrationEnvelope<CorrectnessWorkbookBundle> correctnessWorkbook(
            String draftId,
            long revision,
            IntegrationRequestContext context) {
        context.requireComplete();
        GraphDraft draft = findDraft(draftId, revision, context);
        context.requireDraftScope(draft);
        if (workbookProjection == null) {
            throw new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                    "RG.INTEGRATION.WORKBOOK_PROJECTION_UNAVAILABLE",
                    "Correctness workbook projection is unavailable.", context.correlationId(), Map.of()));
        }
        GraphDraftDependencySnapshotService.Snapshot snapshot = dependencySnapshots.capture(draft);
        try {
            CorrectnessWorkbookBundle bundle = workbookProjection.project(
                    draft, draftFingerprint(draft), snapshot);
            verifySnapshotStable(draft, revision, snapshot, context);
            return IntegrationEnvelope.of("CORRECTNESS_WORKBOOK_BUNDLE",
                    CorrectnessWorkbookBundle.SCHEMA_VERSION, bundle);
        } catch (CorrectnessWorkbookProjectionService.ProjectionException failure) {
            throw new IntegrationProblemException(IntegrationProblem.conflict(
                    "RG.INTEGRATION.WORKBOOK_SOURCE_CHANGED",
                    "A workbook source changed while the immutable bundle was being projected.",
                    context.correlationId(), Map.of("reason", failure.code())));
        }
    }

    /**
     * Exports one exact payload-free semantic suite and its verified terminal evidence to ANEKE.
     *
     * @param suiteId stable immutable suite id
     * @param revision exact suite revision
     * @param context verified Tool Studio workload scope
     * @return semantic correctness workbook envelope
     */
    public IntegrationEnvelope<SemanticCorrectnessWorkbookBundle> semanticCorrectnessWorkbook(
            String suiteId, long revision, IntegrationRequestContext context) {
        context.requireComplete();
        if (semanticWorkbookProjection == null) {
            throw new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                    "RG.INTEGRATION.SEMANTIC_WORKBOOK_UNAVAILABLE",
                    "Semantic workbook projection requires the isolated test runtime.",
                    context.correlationId(), Map.of()));
        }
        try {
            SemanticCorrectnessWorkbookBundle bundle =
                    semanticWorkbookProjection.project(suiteId, revision, context);
            return IntegrationEnvelope.of("SEMANTIC_CORRECTNESS_WORKBOOK_BUNDLE",
                    SemanticCorrectnessWorkbookBundle.SCHEMA_VERSION, bundle);
        } catch (SemanticCorrectnessWorkbookProjectionService.ProjectionException failure) {
            throw new IntegrationProblemException(IntegrationProblem.conflict(
                    "RG.INTEGRATION.SEMANTIC_WORKBOOK_SOURCE_INVALID",
                    "The semantic workbook source cannot produce trusted governance facts.",
                    context.correlationId(), Map.of("reason", failure.code())));
        } catch (SemanticCorrectnessWorkbookProjectionService.StoreUnavailableException failure) {
            throw new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                    "RG.INTEGRATION.SEMANTIC_WORKBOOK_STORE_UNAVAILABLE",
                    "Semantic suite-run history is unavailable.", context.correlationId(), Map.of()));
        }
    }

    public IntegrationEnvelope<RunEvidenceBundle> runEvidence(String runId,
                                                              IntegrationRequestContext context) {
        VisualGraphRunRecord record = findRun(runId, context);
        VisualRunPayloadStatus payloadStatus = payloadStatus(record);
        return IntegrationEnvelope.of("RUN_EVIDENCE_BUNDLE", RunEvidenceBundle.SCHEMA_VERSION,
                RunEvidenceBundle.from(record, runRepository.evidenceSigner(), payloadStatus));
    }

    public IntegrationEnvelope<PayloadReplayBundle> replay(String runId,
                                                           IntegrationRequestContext context) {
        VisualGraphRunRecord record = findRun(runId, context);
        GovernedPayload governed = governedPayload(record, context);
        return IntegrationEnvelope.of("PAYLOAD_REPLAY_BUNDLE", PayloadReplayBundle.SCHEMA_VERSION,
                PayloadReplayBundle.from(governed.record(), governed.status()));
    }

    public synchronized IntegrationEnvelope<ReplayExecutionResult> executeReplay(
            String parentRunId,
            ReplayExecutionRequest request,
            IntegrationRequestContext context) {
        context.requireComplete();
        requirePurpose(context, "PAYLOAD_REPLAY");
        VisualGraphRunRecord parent = findRun(parentRunId, context);
        GovernedPayload governedParent = governedPayload(parent, context);
        parent = governedParent.record();
        validateReplayRequest(request, parent, context);
        String requestFingerprint = request.fingerprint();
        VisualGraphRunRecord existing = replayByRequest(request.requestId(), context);
        if (existing != null) {
            if (existing.replay().parentRunId().equals(parentRunId)
                    && existing.replay().requestFingerprint().equals(requestFingerprint)) {
                return replayEnvelope(existing);
            }
            throw new IntegrationProblemException(IntegrationProblem.conflict(
                    "RG.INTEGRATION.REPLAY_REQUEST_ID_CONFLICT",
                    "Replay request id already identifies different immutable content.",
                    context.correlationId(), Map.of("requestId", request.requestId())
            ));
        }

        RunEvidenceBundle parentEvidence = RunEvidenceBundle.from(parent, runRepository.evidenceSigner());
        List<VisualReplayAssertionResult> assertionResults = replayAssertionEvaluator.evaluate(
                request, parent, parentEvidence);
        VisualReplayMetadata replayMetadata = new VisualReplayMetadata(
                "", parentRunId, request.requestId(), requestFingerprint, request.mode(), request.caseType(),
                request.externalSideEffectPolicy(), 0, assertionResults);
        String replayRunId = deterministicReplayRunId(context.tenantId(), parentRunId, request.requestId());
        VisualGraphRunRecord replayRecord = parent.recordedReplay(replayMetadata)
                .withIdentity(replayRunId, Instant.now());
        try {
            return replayEnvelope(runRepository.create(replayRecord));
        } catch (RuntimeException concurrentCreate) {
            VisualGraphRunRecord winner = runRepository.find(replayRunId).orElse(null);
            if (winner == null) throw concurrentCreate;
            if (context.tenantId().equals(winner.tenantId())
                    && context.environmentId().equals(winner.environment())
                    && winner.replay().parentRunId().equals(parentRunId)
                    && winner.replay().requestFingerprint().equals(requestFingerprint)) {
                return replayEnvelope(winner);
            }
            throw new IntegrationProblemException(IntegrationProblem.conflict(
                    "RG.INTEGRATION.REPLAY_REQUEST_ID_CONFLICT",
                    "Replay request id already identifies different immutable content.",
                    context.correlationId(), Map.of("requestId", request.requestId())));
        }
    }

    public IntegrationEnvelope<PayloadRetentionView> payloadRetention(String runId,
                                                                      IntegrationRequestContext context) {
        VisualGraphRunRecord run = findRun(runId, context);
        VisualRunPayloadRepository payloads = requirePayloadRepository(context);
        VisualRunPayloadStatus status = payloads.status(run.runId()).orElseThrow(() -> payloadUnavailable(
                context, "NOT_GOVERNED", run.payloadRetention().classification(), run.payloadRetention().expiresAt()));
        return IntegrationEnvelope.of("PAYLOAD_RETENTION_VIEW", PayloadRetentionView.SCHEMA_VERSION,
                new PayloadRetentionView("", status, payloads.events(run.runId())));
    }

    public IntegrationEnvelope<PayloadRetentionView> placePayloadHold(String runId,
                                                                      PayloadLifecycleCommand command,
                                                                      IntegrationRequestContext context) {
        requirePurpose(context, "LEGAL_HOLD");
        VisualGraphRunRecord run = findRun(runId, context);
        PayloadLifecycleCommand safe = requirePayloadCommand(command, true, context);
        try {
            VisualRunPayloadRepository payloads = requirePayloadRepository(context);
            VisualRunPayloadStatus status = payloads.placeHold(run.runId(), safe.requestId(), safe.holdId(),
                    context.actorId(), safe.reason(), Instant.now());
            return IntegrationEnvelope.of("PAYLOAD_RETENTION_VIEW", PayloadRetentionView.SCHEMA_VERSION,
                    new PayloadRetentionView("", status, payloads.events(run.runId())));
        } catch (VisualPayloadGovernanceException failure) {
            throw mapPayloadFailure(failure, context);
        }
    }

    public IntegrationEnvelope<PayloadRetentionView> releasePayloadHold(String runId,
                                                                        String holdId,
                                                                        PayloadLifecycleCommand command,
                                                                        IntegrationRequestContext context) {
        requirePurpose(context, "LEGAL_HOLD");
        VisualGraphRunRecord run = findRun(runId, context);
        PayloadLifecycleCommand safe = requirePayloadCommand(command, false, context);
        try {
            VisualRunPayloadRepository payloads = requirePayloadRepository(context);
            VisualRunPayloadStatus status = payloads.releaseHold(run.runId(), safe.requestId(), holdId,
                    context.actorId(), safe.reason(), Instant.now());
            return IntegrationEnvelope.of("PAYLOAD_RETENTION_VIEW", PayloadRetentionView.SCHEMA_VERSION,
                    new PayloadRetentionView("", status, payloads.events(run.runId())));
        } catch (VisualPayloadGovernanceException failure) {
            throw mapPayloadFailure(failure, context);
        }
    }

    public IntegrationEnvelope<PayloadRetentionView> purgePayload(String runId,
                                                                  PayloadLifecycleCommand command,
                                                                  IntegrationRequestContext context) {
        requirePurpose(context, "PAYLOAD_RETENTION_ADMIN");
        VisualGraphRunRecord run = findRun(runId, context);
        PayloadLifecycleCommand safe = requirePayloadCommand(command, false, context);
        try {
            VisualRunPayloadRepository payloads = requirePayloadRepository(context);
            VisualRunPayloadStatus status = payloads.purge(run.runId(), safe.requestId(), context.actorId(),
                    safe.reason(), Instant.now());
            return IntegrationEnvelope.of("PAYLOAD_RETENTION_VIEW", PayloadRetentionView.SCHEMA_VERSION,
                    new PayloadRetentionView("", status, payloads.events(run.runId())));
        } catch (VisualPayloadGovernanceException failure) {
            throw mapPayloadFailure(failure, context);
        }
    }

    public IntegrationEnvelope<PayloadRetentionSweepResult> purgeExpiredPayloads(
            IntegrationRequestContext context) {
        context.requireComplete();
        requirePurpose(context, "PAYLOAD_RETENTION_ADMIN");
        Instant observedAt = Instant.now();
        int purged = requirePayloadRepository(context).purgeExpired(observedAt, 500);
        return IntegrationEnvelope.of("PAYLOAD_RETENTION_SWEEP_RESULT",
                PayloadRetentionSweepResult.SCHEMA_VERSION,
                new PayloadRetentionSweepResult("", observedAt, purged));
    }

    public IntegrationEnvelope<VisualEvidenceSigner.VerificationKey> evidenceKey(String keyId) {
        VisualEvidenceSigner signer = runRepository == null
                ? VisualEvidenceSigner.unavailable()
                : runRepository.evidenceSigner();
        VisualEvidenceSigner.KeyResolution resolution = signer.resolveKey(keyId);
        if (resolution.status() == VisualEvidenceSigner.KeyResolutionStatus.PROVIDER_UNAVAILABLE) {
            throw new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                    "RG.INTEGRATION.EVIDENCE_KEY_PROVIDER_UNAVAILABLE",
                    "Evidence verification key provider is unavailable.", "",
                    Map.of("keyId", keyId == null ? "" : keyId, "reason", resolution.reason())
            ));
        }
        if (resolution.status() != VisualEvidenceSigner.KeyResolutionStatus.AVAILABLE) {
            throw new IntegrationProblemException(IntegrationProblem.notFound(
                    "RG.INTEGRATION.EVIDENCE_KEY_NOT_FOUND",
                    "Evidence verification key was not found.", "", Map.of("keyId", keyId == null ? "" : keyId)
            ));
        }
        VisualEvidenceSigner.VerificationKey key = resolution.key();
        return IntegrationEnvelope.of("EVIDENCE_VERIFICATION_KEY",
                VisualEvidenceSigner.VerificationKey.SCHEMA_VERSION, key);
    }

    /**
     * Publishes one atomic, signed multi-key lifecycle snapshot for pinned offline verification.
     *
     * <p>The returned fingerprint is the external trust pin. Consumers must not trust embedded
     * keys solely because the snapshot is signed by one of those same keys.</p>
     *
     * @return signed public key policy without private material
     */
    public IntegrationEnvelope<EvidenceVerificationKeySet> evidenceKeySet() {
        EvidenceVerificationKeySet keySet = currentEvidenceKeySet();
        return IntegrationEnvelope.of("EVIDENCE_VERIFICATION_KEY_SET",
                EvidenceVerificationKeySet.SCHEMA_VERSION, keySet);
    }

    /**
     * Appends one externally signed pin policy after quorum, key-set binding, and chain validation.
     *
     * @param publication untrusted governance publication candidate
     * @param context authenticated evidence-trust administration context
     * @return durable publication, idempotently reused when fingerprint-identical
     */
    public IntegrationEnvelope<EvidenceKeySetTrustPublication> publishEvidenceKeySetTrust(
            EvidenceKeySetTrustPublication publication,
            IntegrationRequestContext context) {
        context.requireComplete();
        requirePurpose(context, "EVIDENCE_TRUST_ADMIN");
        requireEvidenceTrustAvailable(context.correlationId());
        EvidenceKeySetTrustStore.Verification verification = evidenceTrustStore.verify(
                publication, Instant.now());
        if (verification.status() == EvidenceKeySetTrustStore.VerificationStatus.UNAVAILABLE) {
            throw evidenceTrustUnavailable(context.correlationId(), verification.reasonCode());
        }
        if (!verification.verified()) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.INTEGRATION.EVIDENCE_TRUST_PUBLICATION_REJECTED",
                    "Evidence trust publication failed independent policy verification.",
                    context.correlationId(), Map.of("reasonCode", verification.reasonCode(),
                            "validSignatureCount", verification.validSignatureCount(),
                            "requiredSignatureCount", verification.requiredSignatureCount())));
        }
        EvidenceVerificationKeySet keySet = currentEvidenceKeySet();
        EvidenceKeySetTrustPublication.SnapshotPin activePin = publication.pins().stream()
                .filter(pin -> pin.state() == EvidenceKeySetTrustPublication.PinState.ACTIVE)
                .findFirst().orElseThrow();
        if (!activePin.snapshotFingerprint().equals(keySet.snapshotFingerprint())) {
            throw new IntegrationProblemException(IntegrationProblem.conflict(
                    "RG.INTEGRATION.EVIDENCE_TRUST_KEY_SET_STALE",
                    "Evidence trust publication does not authorize the current key-set snapshot.",
                    context.correlationId(), Map.of("publicationSequence", publication.sequence())));
        }
        try {
            EvidenceKeySetTrustPublication stored = evidenceTrustPublications.append(publication);
            return IntegrationEnvelope.of("EVIDENCE_KEY_SET_TRUST_PUBLICATION",
                    EvidenceKeySetTrustPublication.SCHEMA_VERSION, stored);
        } catch (EvidenceKeySetTrustChain.ChainViolation violation) {
            throw new IntegrationProblemException(IntegrationProblem.conflict(
                    "RG.INTEGRATION.EVIDENCE_TRUST_CHAIN_CONFLICT",
                    "Evidence trust publication is not the unique successor of the durable head.",
                    context.correlationId(), Map.of("reasonCode", violation.reason().name(),
                            "publicationSequence", publication.sequence())));
        }
    }

    /**
     * Returns a bounded consistency page and current key-set snapshot at one observed log head.
     *
     * @param afterSequence caller's durable checkpoint sequence
     * @param limit maximum publications returned in this page
     * @return key set plus append-only consistency proof page
     */
    public IntegrationEnvelope<EvidenceKeySetTrustBundle> evidenceKeySetTrustBundle(
            long afterSequence, int limit) {
        requireEvidenceTrustAvailable("");
        if (afterSequence < 0 || limit < 1 || limit > EvidenceKeySetTrustBundle.MAX_PUBLICATIONS) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.INTEGRATION.EVIDENCE_TRUST_CURSOR_INVALID",
                    "Evidence trust cursor or page limit is invalid.", "",
                    Map.of("maximumLimit", EvidenceKeySetTrustBundle.MAX_PUBLICATIONS)));
        }
        EvidenceKeySetTrustStore.Descriptor descriptor = evidenceTrustStore.descriptor();
        EvidenceKeySetTrustPublication head = evidenceTrustPublications.latest(descriptor.logId())
                .orElseThrow(() -> new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                        "RG.INTEGRATION.EVIDENCE_TRUST_PUBLICATION_UNAVAILABLE",
                        "No authorized evidence trust publication is available.", "", Map.of())));
        if (afterSequence > head.sequence()) {
            throw new IntegrationProblemException(IntegrationProblem.conflict(
                    "RG.INTEGRATION.EVIDENCE_TRUST_CURSOR_AHEAD",
                    "Evidence trust cursor is ahead of the observed log head.", "",
                    Map.of("highWaterSequence", head.sequence())));
        }
        EvidenceKeySetTrustStore.Verification headVerification = evidenceTrustStore.verify(
                head, Instant.now());
        if (!headVerification.verified()) {
            throw evidenceTrustUnavailable("", headVerification.reasonCode());
        }
        EvidenceVerificationKeySet keySet = currentEvidenceKeySet();
        EvidenceKeySetTrustPublication.SnapshotPin activePin = head.pins().stream()
                .filter(pin -> pin.state() == EvidenceKeySetTrustPublication.PinState.ACTIVE)
                .findFirst().orElseThrow();
        if (!activePin.snapshotFingerprint().equals(keySet.snapshotFingerprint())
                || !activePin.acceptedAt(Instant.now())) {
            throw new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                    "RG.INTEGRATION.EVIDENCE_TRUST_KEY_SET_STALE",
                    "The authorized trust head does not bind the current key-set snapshot.", "",
                    Map.of("highWaterSequence", head.sequence())));
        }
        List<EvidenceKeySetTrustPublication> page = evidenceTrustPublications.readAfter(
                        descriptor.logId(), afterSequence, limit).stream()
                .takeWhile(publication -> publication.sequence() <= head.sequence()).toList();
        long throughSequence = page.isEmpty() ? afterSequence : page.getLast().sequence();
        EvidenceKeySetTrustBundle bundle = new EvidenceKeySetTrustBundle("", Instant.now(),
                descriptor.trustDomain(), descriptor.logId(), afterSequence, throughSequence,
                head.sequence(), head.publicationFingerprint(), head, throughSequence < head.sequence(),
                page, keySet);
        return IntegrationEnvelope.of("EVIDENCE_KEY_SET_TRUST_BUNDLE",
                EvidenceKeySetTrustBundle.SCHEMA_VERSION, bundle);
    }

    private EvidenceVerificationKeySet currentEvidenceKeySet() {
        VisualEvidenceSigner signer = runRepository == null
                ? VisualEvidenceSigner.unavailable()
                : runRepository.evidenceSigner();
        VisualEvidenceSigner.KeySetResolution resolution = signer.resolveKeySet();
        if (resolution.status() != VisualEvidenceSigner.KeyResolutionStatus.AVAILABLE
                || resolution.keySet() == null) {
            throw new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                    "RG.INTEGRATION.EVIDENCE_KEY_SET_PROVIDER_UNAVAILABLE",
                    "Evidence verification key-set provider is unavailable.", "",
                    Map.of("reason", resolution.reason())
            ));
        }
        try {
            EvidenceVerificationKeySet keySet = EvidenceVerificationKeySet.publish(
                    objectMapper, signer, resolution.keySet());
            return keySet;
        } catch (RuntimeException failure) {
            throw new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                    "RG.INTEGRATION.EVIDENCE_KEY_SET_ATTESTATION_UNAVAILABLE",
                    "Evidence verification key set could not be signed and verified.", "",
                    Map.of("reason", failure.getClass().getSimpleName())
            ));
        }
    }

    private void requireEvidenceTrustAvailable(String correlationId) {
        if (!evidenceTrustStore.descriptor().available() || evidenceTrustPublications == null
                || !evidenceTrustPublications.available()) {
            throw evidenceTrustUnavailable(correlationId, "TRUST_STORE_UNAVAILABLE");
        }
    }

    private static IntegrationProblemException evidenceTrustUnavailable(
            String correlationId, String reasonCode) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                "RG.INTEGRATION.EVIDENCE_TRUST_UNAVAILABLE",
                "Independent evidence trust verification is unavailable.", correlationId,
                Map.of("reasonCode", reasonCode == null ? "UNAVAILABLE" : reasonCode)));
    }

    public IntegrationEnvelope<GovernanceGateResult> submitGateResult(GovernanceGateResult result,
                                                                      IntegrationRequestContext context) {
        context.requireComplete();
        requirePurpose(context, "GOVERNANCE_GATE_FEEDBACK");
        validateGateResult(result, context);
        GraphDraft targetDraft = findDraft(result.target().draftId(), result.target().revision(), context);
        context.requireDraftScope(targetDraft);
        String actualFingerprint = draftFingerprint(targetDraft);
        if (!actualFingerprint.equals(result.target().draftFingerprint())) {
            throw new IntegrationProblemException(IntegrationProblem.conflict(
                    "RG.INTEGRATION.GATE_TARGET_STALE",
                    "Governance gate result targets a different draft snapshot.", context.correlationId(),
                    Map.of("draftId", targetDraft.draftId(), "revision", targetDraft.revision(),
                            "expectedDraftFingerprint", actualFingerprint)
            ));
        }
        GovernanceGateResult existing = gateResultRepository.find(result.gateResultId()).orElse(null);
        if (existing != null) {
            if (existing.resultFingerprint().equals(result.resultFingerprint())) {
                return IntegrationEnvelope.of("GOVERNANCE_GATE_RESULT", existing.schemaVersion(),
                        existing);
            }
            throw new IntegrationProblemException(IntegrationProblem.conflict(
                    "RG.INTEGRATION.GATE_RESULT_ID_CONFLICT",
                    "Gate result id already identifies different immutable content.", context.correlationId(),
                    Map.of("gateResultId", result.gateResultId())
            ));
        }
        GraphDraftDependencySnapshotService.Snapshot gateSnapshot =
                !GovernanceGateResult.SCHEMA_VERSION_V1.equals(result.schemaVersion())
                        ? dependencySnapshots.capture(targetDraft) : null;
        validateGateDecisionBasis(result, targetDraft, actualFingerprint, gateSnapshot, context);
        if (gateSnapshot != null) {
            verifySnapshotStable(targetDraft, targetDraft.revision(), gateSnapshot, context);
        }
        if (GovernanceGateResult.SCHEMA_VERSION.equals(result.schemaVersion())) {
            verifySemanticTargetsStable(result.decisionBasis().semanticWorkbooks(), targetDraft, context);
        }
        GovernanceGateResult stored;
        try {
            stored = gateResultRepository.create(result);
        } catch (IllegalArgumentException conflict) {
            throw new IntegrationProblemException(IntegrationProblem.conflict(
                    "RG.INTEGRATION.GATE_RESULT_ID_CONFLICT",
                    "Gate result id already identifies different immutable content.", context.correlationId(),
                    Map.of("gateResultId", result.gateResultId())));
        }
        return IntegrationEnvelope.of("GOVERNANCE_GATE_RESULT", stored.schemaVersion(), stored);
    }

    public IntegrationEnvelope<GovernanceGateView> governanceGate(String draftId,
                                                                  IntegrationRequestContext context) {
        context.requireComplete();
        GraphDraft draft = findDraft(draftId, 0, context);
        context.requireDraftScope(draft);
        GovernanceGateView view = governanceGateView(draft);
        return IntegrationEnvelope.of("GOVERNANCE_GATE_VIEW", GovernanceGateView.SCHEMA_VERSION, view);
    }

    public GovernanceGateView authoringGovernanceGate(String draftId) {
        if (draftRepository == null) {
            return new GovernanceGateView("", draftId, 0, "", "MISSING", null);
        }
        GraphDraft draft = draftRepository.find(draftId).orElse(null);
        return draft == null
                ? new GovernanceGateView("", draftId, 0, "", "MISSING", null)
                : governanceGateView(draft);
    }

    private GovernanceGateView governanceGateView(GraphDraft draft) {
        String currentFingerprint = draftFingerprint(draft);
        GovernanceGateResult latest = gateResultRepository == null
                ? null
                : gateResultRepository.forDraft(draft.draftId()).stream().findFirst().orElse(null);
        String freshness = "MISSING";
        if (latest != null) {
            if (latest.expiresAt() != null && !latest.expiresAt().isAfter(Instant.now())) {
                freshness = "EXPIRED";
            } else if (latest.target().revision() != draft.revision()
                    || !latest.target().draftFingerprint().equals(currentFingerprint)) {
                freshness = "STALE";
            } else if (!latest.decisionBasis().dependencySnapshotFingerprint().isBlank()
                    && !latest.decisionBasis().dependencySnapshotFingerprint()
                    .equals(dependencySnapshots.capture(draft).fingerprint())) {
                freshness = "STALE";
            } else if (GovernanceGateResult.SCHEMA_VERSION.equals(latest.schemaVersion())
                    && !latest.decisionBasis().semanticWorkbooks().isEmpty()) {
                freshness = semanticGateFreshness(latest, draft);
            } else {
                freshness = "CURRENT";
            }
        }
        return new GovernanceGateView("", draft.draftId(), draft.revision(), currentFingerprint, freshness, latest);
    }

    private String semanticGateFreshness(GovernanceGateResult result, GraphDraft draft) {
        if (semanticWorkbookProjection == null || semanticGateTargetVerifier == null) {
            return "UNVERIFIABLE";
        }
        IntegrationRequestContext internal = internalGateVerificationContext(draft);
        try {
            for (GovernanceGateResult.SemanticWorkbookRef reference
                    : result.decisionBasis().semanticWorkbooks()) {
                semanticWorkbookProjection.verifyDecisionBasis(reference, internal);
                if (!semanticGateTargetVerifier.verify(draft, reference.target()).matched()) {
                    return "STALE";
                }
            }
            return "CURRENT";
        } catch (SemanticCorrectnessWorkbookProjectionService.StoreUnavailableException unavailable) {
            return "UNVERIFIABLE";
        } catch (IntegrationProblemException sourceFailure) {
            return sourceFailure.problem().status() >= 500 ? "UNVERIFIABLE" : "STALE";
        } catch (SemanticCorrectnessWorkbookProjectionService.ProjectionException stale) {
            return "STALE";
        } catch (RuntimeException unavailable) {
            return "UNVERIFIABLE";
        }
    }

    private static IntegrationRequestContext internalGateVerificationContext(GraphDraft draft) {
        return new IntegrationRequestContext(draft.tenantId(), "resource-gateway",
                draft.namespace(), draft.environment(), "local", "SERVICE",
                "resource-gateway-gate-verifier", "", "WORKBOOK_SYNC",
                "gate-freshness-" + draft.draftId(), Set.of(), "RESTRICTED", "");
    }

    private static void validateGateResult(GovernanceGateResult result, IntegrationRequestContext context) {
        Map<String, Object> invalid = new LinkedHashMap<>();
        if (result == null) {
            invalid.put("result", "required");
        } else {
            if (!Set.of(GovernanceGateResult.SCHEMA_VERSION_V1,
                    GovernanceGateResult.SCHEMA_VERSION_V2, GovernanceGateResult.SCHEMA_VERSION)
                    .contains(result.schemaVersion())) {
                invalid.put("schemaVersion", GovernanceGateResult.SCHEMA_VERSION_V1 + "|"
                        + GovernanceGateResult.SCHEMA_VERSION_V2 + "|"
                        + GovernanceGateResult.SCHEMA_VERSION);
            }
            if (result.gateResultId().isBlank()) invalid.put("gateResultId", "required");
            if (!"GRAPH_DRAFT".equals(result.target().kind())) invalid.put("target.kind", "GRAPH_DRAFT");
            if (result.target().draftId().isBlank()) invalid.put("target.draftId", "required");
            if (result.target().revision() <= 0) invalid.put("target.revision", "positive");
            if (result.target().draftFingerprint().isBlank()) invalid.put("target.draftFingerprint", "required");
            if (!Set.of("PASSED", "BLOCKED", "WARNING", "UNKNOWN").contains(result.status())) {
                invalid.put("status", "PASSED|BLOCKED|WARNING|UNKNOWN");
            }
            if (!GovernanceGateResult.SCHEMA_VERSION_V1.equals(result.schemaVersion())) {
                if (result.target().tenantId().isBlank()) invalid.put("target.tenantId", "required");
                if (result.target().namespace().isBlank()) invalid.put("target.namespace", "required");
                if (result.target().environment().isBlank()) invalid.put("target.environment", "required");
            }
            if (GovernanceGateResult.SCHEMA_VERSION.equals(result.schemaVersion())) {
                validateSemanticReferenceShapes(result.decisionBasis().semanticWorkbooks(), invalid);
            } else if (!result.decisionBasis().semanticWorkbooks().isEmpty()) {
                invalid.put("decisionBasis.semanticWorkbooks", "gateResult.v3 required");
            }
            if (!result.fingerprintVerified()) invalid.put("resultFingerprint", "does not match content");
            if (GovernanceGateResult.SCHEMA_VERSION_V1.equals(result.schemaVersion())
                    && "PASSED".equals(result.status())) {
                invalid.put("decisionBasis", "gateResult.v2 or later is required for PASSED decisions");
            }
        }
        if (!invalid.isEmpty()) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.INTEGRATION.GATE_RESULT_INVALID", "Governance gate result is invalid.",
                    context.correlationId(), invalid));
        }
    }

    private static void validateSemanticReferenceShapes(
            List<GovernanceGateResult.SemanticWorkbookRef> references,
            Map<String, Object> invalid) {
        if (references.size() > 100) {
            invalid.put("decisionBasis.semanticWorkbooks", "maximum 100");
            return;
        }
        for (int index = 0; index < references.size(); index++) {
            GovernanceGateResult.SemanticWorkbookRef reference = references.get(index);
            String path = "decisionBasis.semanticWorkbooks[" + index + "]";
            if (reference == null) {
                invalid.put(path, "required");
                continue;
            }
            if (reference.suite() == null || reference.suite().suiteId().isBlank()
                    || reference.suite().revision() <= 0
                    || !validSha256(reference.suite().fingerprint())) {
                invalid.put(path + ".suite", "exact suite id, revision and fingerprint required");
            }
            if (reference.target() == null
                    || !Set.of("GRAPH", "OPERATOR").contains(reference.target().kind())
                    || reference.target().id().isBlank()
                    || !validSha256(reference.target().fingerprint())) {
                invalid.put(path + ".target", "exact GRAPH or OPERATOR target required");
            }
            if (!validSha256(reference.bundleFingerprint())) {
                invalid.put(path + ".bundleFingerprint", "sha256 fingerprint required");
            }
            if (!Set.of("READY", "NO_TERMINAL_EVIDENCE", "VERIFICATION_UNAVAILABLE",
                    "NO_ELIGIBLE_EVIDENCE").contains(reference.projectionStatus())) {
                invalid.put(path + ".projectionStatus", "unsupported");
            }
            if (reference.evidence().size() > 100) {
                invalid.put(path + ".evidence", "maximum 100");
            }
            for (int evidenceIndex = 0; evidenceIndex < reference.evidence().size(); evidenceIndex++) {
                GovernanceGateResult.SemanticEvidenceRef evidence = reference.evidence().get(evidenceIndex);
                if (evidence == null || evidence.suiteRunId().isBlank()
                        || !validSha256(evidence.evidenceFingerprint())) {
                    invalid.put(path + ".evidence[" + evidenceIndex + "]",
                            "suiteRunId and exact evidence fingerprint required");
                }
            }
        }
    }

    private static boolean validSha256(String value) {
        return value != null && value.matches("sha256:[0-9a-f]{64}");
    }

    private void validateGateDecisionBasis(GovernanceGateResult result,
                                           GraphDraft draft,
                                           String draftFingerprint,
                                           GraphDraftDependencySnapshotService.Snapshot snapshot,
                                           IntegrationRequestContext context) {
        if (GovernanceGateResult.SCHEMA_VERSION_V1.equals(result.schemaVersion())) return;
        GovernanceGateResult.DecisionBasis basis = result.decisionBasis();
        boolean passed = "PASSED".equals(result.status());
        if (!draft.tenantId().equals(result.target().tenantId())
                || !draft.namespace().equals(result.target().namespace())
                || !draft.environment().equals(result.target().environment())) {
            throw gateBasisConflict(context, "TARGET_SCOPE_MISMATCH");
        }
        if (!basis.dependencySnapshotFingerprint().isBlank()
                && !basis.dependencySnapshotFingerprint().equals(snapshot.fingerprint())) {
            throw gateBasisConflict(context, "DEPENDENCY_SNAPSHOT_STALE");
        }
        Set<String> currentSuites = currentSuiteKeys(snapshot);
        Set<String> suppliedSuites = basis.contractSuites().stream()
                .map(GovernanceGateResult.SuiteRef::key).collect(java.util.stream.Collectors.toSet());
        if ((!suppliedSuites.isEmpty() && !currentSuites.containsAll(suppliedSuites))
                || passed && basis.policy().requiredChecks().contains("CONTRACT_COVERAGE")
                && !currentSuites.equals(suppliedSuites)) {
            throw gateBasisConflict(context, "CONTRACT_SUITE_STALE");
        }
        CorrectnessWorkbookBundle workbook = null;
        if (!basis.workbook().sourceBundleFingerprint().isBlank() || passed) {
            if (workbookProjection == null) {
                throw new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                        "RG.INTEGRATION.WORKBOOK_PROJECTION_UNAVAILABLE",
                        "Correctness workbook projection is unavailable.", context.correlationId(), Map.of()));
            }
            try {
                workbook = workbookProjection.project(draft, draftFingerprint, snapshot);
            } catch (CorrectnessWorkbookProjectionService.ProjectionException staleProjection) {
                throw gateBasisConflict(context, staleProjection.code());
            }
            if (!basis.workbook().sourceBundleFingerprint().isBlank()
                    && !basis.workbook().sourceBundleFingerprint().equals(workbook.manifest().bundleFingerprint())) {
                throw gateBasisConflict(context, "WORKBOOK_SOURCE_STALE");
            }
        }
        for (GovernanceGateResult.EvidenceRef ref : basis.evidence()) {
            VisualGraphRunRecord run = findRun(ref.runId(), context);
            boolean sameDraft = draft.draftId().equals(run.draftId())
                    && draft.revision() == run.draftRevision()
                    && draftFingerprint.equals(run.draftFingerprint());
            boolean verified = run.evidenceMaterialFingerprint().equals(ref.evidenceFingerprint())
                    && runRepository.evidenceSigner()
                    .verify(run.evidenceSeal(), run.evidenceMaterialFingerprint()).valid();
            if (!sameDraft || !verified) throw gateBasisConflict(context, "EVIDENCE_REF_INVALID");
        }
        SemanticBasisSummary semantic = GovernanceGateResult.SCHEMA_VERSION.equals(result.schemaVersion())
                ? validateSemanticDecisionBasis(basis.semanticWorkbooks(), draft, context)
                : SemanticBasisSummary.empty();
        if (!passed) return;
        Map<String, Object> incomplete = new LinkedHashMap<>();
        if (!basis.workbook().complete()) incomplete.put("workbook", "complete ref required");
        if (basis.dependencySnapshotFingerprint().isBlank()) {
            incomplete.put("dependencySnapshotFingerprint", "required");
        }
        if (!basis.policy().complete()) incomplete.put("policy", "id, version and requiredChecks required");
        List<String> failedChecks = basis.failedRequiredChecks();
        if (!failedChecks.isEmpty()) incomplete.put("failedRequiredCheckCount", failedChecks.size());
        if (basis.checks().stream().anyMatch(check -> Set.of("BLOCKED", "FAILED").contains(check.status()))) {
            incomplete.put("checks", "blocking result present");
        }
        if (basis.policy().requiredChecks().contains("EVIDENCE") && basis.evidence().isEmpty()) {
            incomplete.put("evidence", "at least one verified run required");
        }
        if (workbook == null || !workbook.fingerprintVerified()) {
            incomplete.put("workbookSource", "unverified");
        }
        if (GovernanceGateResult.SCHEMA_VERSION.equals(result.schemaVersion())) {
            if (basis.semanticWorkbooks().isEmpty()) {
                incomplete.put("semanticWorkbooks", "at least one exact semantic workbook required");
            }
            if (semantic.graphTargetCount() == 0) {
                incomplete.put("semanticGraphTarget", "an exact graph-level semantic suite is required");
            }
            if (!semantic.allGateReady()) {
                incomplete.put("semanticEvidence", "every semantic workbook must be gate-ready");
            }
            if (!basis.policy().requiredChecks().contains("SEMANTIC_CORRECTNESS")) {
                incomplete.put("semanticPolicy", "SEMANTIC_CORRECTNESS must be a required check");
            }
            Set<String> semanticCheckRefs = basis.checks().stream()
                    .filter(check -> "SEMANTIC_CORRECTNESS".equals(check.kind()))
                    .flatMap(check -> check.refs().stream())
                    .collect(java.util.stream.Collectors.toSet());
            if (!semanticCheckRefs.equals(semantic.bundleFingerprints())) {
                incomplete.put("semanticCheckRefs",
                        "SEMANTIC_CORRECTNESS must reference every exact workbook bundle fingerprint");
            }
        }
        if (!incomplete.isEmpty()) {
            throw new IntegrationProblemException(IntegrationProblem.conflict(
                    "RG.INTEGRATION.GATE_BASIS_INCOMPLETE",
                    "A PASSED gate result must carry a complete, verified decision basis.",
                    context.correlationId(), incomplete));
        }
    }

    private SemanticBasisSummary validateSemanticDecisionBasis(
            List<GovernanceGateResult.SemanticWorkbookRef> references,
            GraphDraft draft,
            IntegrationRequestContext context) {
        if (references.isEmpty()) return SemanticBasisSummary.empty();
        if (semanticWorkbookProjection == null || semanticGateTargetVerifier == null) {
            throw semanticBasisUnavailable(context, "Semantic gate verification is unavailable.");
        }
        Set<String> suiteKeys = new HashSet<>();
        Set<String> bundleFingerprints = new HashSet<>();
        int graphTargets = 0;
        boolean allGateReady = true;
        for (GovernanceGateResult.SemanticWorkbookRef reference : references) {
            if (!suiteKeys.add(reference.key())
                    || !bundleFingerprints.add(reference.bundleFingerprint())) {
                throw gateBasisConflict(context, "SEMANTIC_WORKBOOK_REF_DUPLICATE");
            }
            SemanticCorrectnessWorkbookBundle bundle;
            try {
                bundle = semanticWorkbookProjection.verifyDecisionBasis(reference, context);
            } catch (SemanticCorrectnessWorkbookProjectionService.StoreUnavailableException unavailable) {
                throw semanticBasisUnavailable(context, "Semantic evidence verification is unavailable.");
            } catch (SemanticCorrectnessWorkbookProjectionService.ProjectionException stale) {
                throw gateBasisConflict(context, stale.code());
            } catch (IntegrationProblemException sourceFailure) {
                if (sourceFailure.problem().status() >= 500) {
                    throw semanticBasisUnavailable(context, "Semantic evidence stores are unavailable.");
                }
                throw gateBasisConflict(context, "SEMANTIC_SOURCE_REF_INVALID");
            } catch (RuntimeException unavailable) {
                throw semanticBasisUnavailable(context, "Semantic evidence verification is unavailable.");
            }
            SemanticGateTargetVerifier.Verification binding;
            try {
                binding = semanticGateTargetVerifier.verify(draft, reference.target());
            } catch (RuntimeException unavailable) {
                throw semanticBasisUnavailable(context, "Semantic target verification is unavailable.");
            }
            if (!binding.matched()) {
                throw gateBasisConflict(context, binding.reason());
            }
            if ("GRAPH".equals(reference.target().kind())) graphTargets++;
            allGateReady &= bundle.manifest().gateReady();
        }
        return new SemanticBasisSummary(graphTargets, Set.copyOf(bundleFingerprints), allGateReady);
    }

    private void verifySemanticTargetsStable(
            List<GovernanceGateResult.SemanticWorkbookRef> references,
            GraphDraft draft,
            IntegrationRequestContext context) {
        if (references.isEmpty()) return;
        if (semanticGateTargetVerifier == null) {
            throw semanticBasisUnavailable(context, "Semantic target verification is unavailable.");
        }
        for (GovernanceGateResult.SemanticWorkbookRef reference : references) {
            SemanticGateTargetVerifier.Verification binding;
            try {
                binding = semanticGateTargetVerifier.verify(draft, reference.target());
            } catch (RuntimeException unavailable) {
                throw semanticBasisUnavailable(context, "Semantic target verification is unavailable.");
            }
            if (!binding.matched()) {
                throw gateBasisConflict(context, binding.reason());
            }
        }
    }

    private static IntegrationProblemException semanticBasisUnavailable(
            IntegrationRequestContext context, String title) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                "RG.INTEGRATION.SEMANTIC_GATE_VERIFICATION_UNAVAILABLE", title,
                context.correlationId(), Map.of()));
    }

    private record SemanticBasisSummary(int graphTargetCount,
                                        Set<String> bundleFingerprints,
                                        boolean allGateReady) {
        private static SemanticBasisSummary empty() {
            return new SemanticBasisSummary(0, Set.of(), false);
        }
    }

    private static Set<String> currentSuiteKeys(GraphDraftDependencySnapshotService.Snapshot snapshot) {
        return snapshot.assets().values().stream()
                .flatMap(asset -> asset.contractSuites().stream())
                .map(ref -> ref.suiteId() + "@" + ref.revision() + "#" + ref.fingerprint())
                .collect(java.util.stream.Collectors.toSet());
    }

    private static IntegrationProblemException gateBasisConflict(IntegrationRequestContext context,
                                                                  String reason) {
        return new IntegrationProblemException(IntegrationProblem.conflict(
                "RG.INTEGRATION.GATE_BASIS_STALE",
                "Governance gate decision basis no longer matches Resource Gateway facts.",
                context.correlationId(), Map.of("reason", reason)));
    }

    private static void validateReplayRequest(ReplayExecutionRequest request,
                                              VisualGraphRunRecord parent,
                                              IntegrationRequestContext context) {
        Map<String, Object> invalid = new LinkedHashMap<>();
        if (request == null) {
            invalid.put("request", "required");
        } else {
            if (!ReplayExecutionRequest.SCHEMA_VERSION.equals(request.schemaVersion())) {
                invalid.put("schemaVersion", ReplayExecutionRequest.SCHEMA_VERSION);
            }
            if (request.requestId().isBlank()) invalid.put("requestId", "required");
            if (!"RECORDED_ASSERTIONS".equals(request.mode())) invalid.put("mode", "RECORDED_ASSERTIONS");
            if (!Set.of("GOLDEN", "NEGATIVE", "BOUNDARY", "REGRESSION").contains(request.caseType())) {
                invalid.put("caseType", "GOLDEN|NEGATIVE|BOUNDARY|REGRESSION");
            }
            if (!"DENY".equals(request.externalSideEffectPolicy())) {
                invalid.put("externalSideEffectPolicy", "DENY");
            }
            if (request.assertions().isEmpty()) invalid.put("assertions", "at least one assertion required");
            if (request.assertions().size() > 100) invalid.put("assertions", "maximum 100 assertions");
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < request.assertions().size(); i++) {
                ReplayExecutionRequest.Assertion assertion = request.assertions().get(i);
                String prefix = "assertions[" + i + "]";
                if (assertion.assertionId().isBlank()) invalid.put(prefix + ".assertionId", "required");
                if (!ids.add(assertion.assertionId())) invalid.put(prefix + ".assertionId", "duplicate");
                if (!Set.of("OUTPUT", "NODE", "RUN").contains(assertion.scope())) {
                    invalid.put(prefix + ".scope", "OUTPUT|NODE|RUN");
                }
                if ("NODE".equals(assertion.scope())) {
                    if (assertion.nodeId().isBlank()) {
                        invalid.put(prefix + ".nodeId", "required for NODE scope");
                    } else if (!parent.nodeSnapshots().containsKey(assertion.nodeId())
                            && !parent.resultsPayload().containsKey(assertion.nodeId())) {
                        invalid.put(prefix + ".nodeId", "not captured in parent run");
                    }
                }
                if (!Set.of("EQUALS", "PATH_EQUALS", "PATH_EXISTS", "PATH_ABSENT", "MATCHES_SCHEMA",
                        "ERROR_CONTAINS", "GOVERNANCE_EXPECTATION").contains(assertion.mode())) {
                    invalid.put(prefix + ".mode", "unsupported");
                }
                if (assertion.mode().startsWith("PATH_")
                        && (assertion.path().isBlank() || !assertion.path().startsWith("/"))) {
                    invalid.put(prefix + ".path", "JSON Pointer required");
                }
                if (Set.of("ERROR_CONTAINS", "GOVERNANCE_EXPECTATION").contains(assertion.mode())
                        && !"RUN".equals(assertion.scope())) {
                    invalid.put(prefix + ".scope", "RUN required for " + assertion.mode());
                }
            }
        }
        if (!invalid.isEmpty()) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.INTEGRATION.REPLAY_REQUEST_INVALID", "Replay execution request is invalid.",
                    context.correlationId(), invalid));
        }
    }

    private VisualGraphRunRecord replayByRequest(String requestId, IntegrationRequestContext context) {
        if (runRepository == null || requestId == null || requestId.isBlank()) {
            return null;
        }
        return runRepository.all().stream()
                .filter(record -> requestId.equals(record.replay().requestId()))
                .filter(record -> context.tenantId().equals(record.tenantId())
                        && context.environmentId().equals(record.environment()))
                .findFirst()
                .orElse(null);
    }

    private IntegrationEnvelope<ReplayExecutionResult> replayEnvelope(VisualGraphRunRecord replayRecord) {
        RunEvidenceBundle evidence = RunEvidenceBundle.from(replayRecord, runRepository.evidenceSigner(),
                payloadStatus(replayRecord));
        VisualReplayMetadata replay = replayRecord.replay();
        ReplayExecutionResult result = new ReplayExecutionResult(
                "", replayRecord.runId(), replay.parentRunId(), replay.requestId(), replay.requestFingerprint(),
                replay.mode(), replay.caseType(), replay.assertionsPassed() ? "PASSED" : "FAILED",
                replay.sideEffectPolicy(), replay.externalInvocationCount(), replay.assertionResults(),
                evidence.manifest().evidenceStatus(), "/api/integration/runs/" + replayRecord.runId() + "/evidence");
        return IntegrationEnvelope.of("REPLAY_EXECUTION_RESULT", ReplayExecutionResult.SCHEMA_VERSION, result);
    }

    private static String deterministicReplayRunId(String tenantId, String parentRunId, String requestId) {
        String material = String.join("\u0000", tenantId, parentRunId, requestId);
        return "replay-" + UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }

    private static void requirePurpose(IntegrationRequestContext context, String requiredPurpose) {
        if (!requiredPurpose.equals(context.purpose())) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.INTEGRATION.PURPOSE_NOT_ALLOWED", "Integration purpose is not allowed for this operation.",
                    context.correlationId(), Map.of("requiredPurpose", requiredPurpose)));
        }
    }

    private GraphDraft findDraft(String draftId, long revision, IntegrationRequestContext context) {
        if (draftRepository == null) {
            throw notFound(context);
        }
        return (revision > 0
                ? draftRepository.findRevision(draftId, revision)
                : draftRepository.find(draftId))
                .orElseThrow(() -> notFound(context));
    }

    private static IntegrationProblemException notFound(IntegrationRequestContext context) {
        return new IntegrationProblemException(IntegrationProblem.notFound(
                "RG.INTEGRATION.DRAFT_NOT_FOUND",
                "Draft was not found in the authorized integration scope.",
                context == null ? "" : context.correlationId(),
                Map.of()
        ));
    }

    private VisualGraphRunRecord findRun(String runId, IntegrationRequestContext context) {
        context.requireComplete();
        VisualGraphRunRecord record = runRepository == null
                ? null
                : runRepository.find(runId).orElse(null);
        if (record == null
                || !context.tenantId().equals(record.tenantId())
                || !context.environmentId().equals(record.environment())) {
            throw new IntegrationProblemException(IntegrationProblem.notFound(
                    "RG.INTEGRATION.RUN_NOT_FOUND",
                    "Run was not found in the authorized integration scope.",
                    context.correlationId(),
                    Map.of()
            ));
        }
        return record;
    }

    private GovernedPayload governedPayload(VisualGraphRunRecord record, IntegrationRequestContext context) {
        VisualRunPayloadRepository payloads = runRepository == null ? null : runRepository.payloadRepository();
        if (payloads == null) {
            if (record.payloadRetention().disposition().equals(
                    com.leanowtech.bloge.gateway.visual.runtime.VisualPayloadRetentionDescriptor.LEGACY_INLINE)
                    && context.hasClearanceAtLeast("RESTRICTED")) {
                return new GovernedPayload(record, null);
            }
            throw payloadUnavailable(context, "NOT_GOVERNED", record.payloadRetention().classification(),
                    record.payloadRetention().expiresAt());
        }
        try {
            VisualRunPayloadRepository.Access access = payloads.access(record.runId(), Instant.now());
            VisualRunPayloadStatus status = access.status();
            if (status == null || !record.tenantId().equals(status.tenantId())
                    || !record.environment().equals(status.environment())) {
                throw new VisualPayloadGovernanceException(VisualPayloadGovernanceException.Reason.CORRUPT,
                        "Payload scope does not match immutable run evidence");
            }
            requirePayloadAuthorization(status, context);
            if (!access.readable()) {
                throw payloadUnavailable(context, status.state(), status.descriptor().classification(),
                        status.descriptor().expiresAt());
            }
            return new GovernedPayload(record.withPayload(access.payload()), status);
        } catch (IntegrationProblemException failure) {
            throw failure;
        } catch (VisualPayloadGovernanceException failure) {
            throw mapPayloadFailure(failure, context);
        }
    }

    private VisualRunPayloadStatus payloadStatus(VisualGraphRunRecord record) {
        VisualRunPayloadRepository payloads = runRepository == null ? null : runRepository.payloadRepository();
        if (payloads == null) {
            return null;
        }
        try {
            return payloads.access(record.runId(), Instant.now()).status();
        } catch (VisualPayloadGovernanceException failure) {
            return payloads.status(record.runId()).orElse(null);
        }
    }

    private static void requirePayloadAuthorization(VisualRunPayloadStatus status,
                                                    IntegrationRequestContext context) {
        if (!context.hasClearanceAtLeast(status.descriptor().requiredClearance())) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.INTEGRATION.PAYLOAD_CLEARANCE_REQUIRED",
                    "The verified workload identity does not have sufficient payload clearance.",
                    context.correlationId(), Map.of(
                    "classification", status.descriptor().classification(),
                    "requiredClearance", status.descriptor().requiredClearance())));
        }
        Set<String> missingGroups = new HashSet<>(status.descriptor().requiredGroups());
        missingGroups.removeAll(context.groups());
        if (!missingGroups.isEmpty()) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.INTEGRATION.PAYLOAD_GROUP_REQUIRED",
                    "The verified workload identity is outside the payload policy group boundary.",
                    context.correlationId(), Map.of("missingGroupCount", missingGroups.size())));
        }
    }

    private VisualRunPayloadRepository requirePayloadRepository(IntegrationRequestContext context) {
        VisualRunPayloadRepository payloads = runRepository == null ? null : runRepository.payloadRepository();
        if (payloads == null) {
            throw new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                    "RG.INTEGRATION.PAYLOAD_GOVERNANCE_UNAVAILABLE",
                    "Governed payload storage is unavailable.", context.correlationId(), Map.of()));
        }
        return payloads;
    }

    private static PayloadLifecycleCommand requirePayloadCommand(PayloadLifecycleCommand command,
                                                                 boolean requireHoldId,
                                                                 IntegrationRequestContext context) {
        PayloadLifecycleCommand safe = command == null
                ? new PayloadLifecycleCommand("", "", "", "") : command;
        Map<String, Object> invalid = new LinkedHashMap<>();
        if (safe.requestId().isBlank()) invalid.put("requestId", "required");
        if (requireHoldId && safe.holdId().isBlank()) invalid.put("holdId", "required");
        if (safe.reason().isBlank()) invalid.put("reason", "required");
        if (!invalid.isEmpty()) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.INTEGRATION.PAYLOAD_LIFECYCLE_COMMAND_INVALID",
                    "Payload lifecycle command is invalid.", context.correlationId(), invalid));
        }
        return safe;
    }

    private static IntegrationProblemException payloadUnavailable(IntegrationRequestContext context,
                                                                  String state,
                                                                  String classification,
                                                                  Instant expiresAt) {
        return new IntegrationProblemException(IntegrationProblem.gone(
                "RG.INTEGRATION.PAYLOAD_NOT_AVAILABLE",
                "Governed replay payload is no longer available.", context.correlationId(), Map.of(
                "state", state == null ? "UNKNOWN" : state,
                "classification", classification == null ? "UNKNOWN" : classification,
                "expiresAt", expiresAt == null ? Instant.EPOCH : expiresAt)));
    }

    private static IntegrationProblemException mapPayloadFailure(VisualPayloadGovernanceException failure,
                                                                 IntegrationRequestContext context) {
        return switch (failure.reason()) {
            case NOT_FOUND -> payloadUnavailable(context, "NOT_FOUND", "UNKNOWN", Instant.EPOCH);
            case HOLD_CONFLICT, LEGAL_HOLD_ACTIVE, ALREADY_EXISTS -> new IntegrationProblemException(
                    IntegrationProblem.conflict("RG.INTEGRATION.PAYLOAD_LIFECYCLE_CONFLICT",
                            failure.getMessage(), context.correlationId(), Map.of("reason", failure.reason().name())));
            case SIGNING_UNAVAILABLE, CORRUPT -> new IntegrationProblemException(
                    IntegrationProblem.serviceUnavailable("RG.INTEGRATION.PAYLOAD_GOVERNANCE_UNAVAILABLE",
                            "Governed payload lifecycle verification is unavailable.",
                            context.correlationId(), Map.of("reason", failure.reason().name())));
        };
    }

    private record GovernedPayload(VisualGraphRunRecord record, VisualRunPayloadStatus status) {
    }

    private void verifySnapshotStable(GraphDraft draft,
                                      long requestedRevision,
                                      GraphDraftDependencySnapshotService.Snapshot before,
                                      IntegrationRequestContext context) {
        GraphDraftDependencySnapshotService.Snapshot after = dependencySnapshots.capture(draft);
        GraphDraft persisted = (requestedRevision > 0
                ? draftRepository.findRevision(draft.draftId(), draft.revision())
                : draftRepository.find(draft.draftId())).orElse(null);
        boolean draftStable = persisted != null
                && persisted.revision() == draft.revision()
                && draftFingerprint(persisted).equals(draftFingerprint(draft));
        if (draftStable && before.fingerprint().equals(after.fingerprint())) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("draftId", draft.draftId());
        details.put("observedRevision", draft.revision());
        details.put("requestedRevision", requestedRevision);
        details.put("beforeDependencyFingerprint", before.fingerprint());
        details.put("afterDependencyFingerprint", after.fingerprint());
        details.put("draftStable", draftStable);
        throw new IntegrationProblemException(IntegrationProblem.retryableConflict(
                "RG.INTEGRATION.DRAFT_SNAPSHOT_CHANGED",
                "Draft dependencies changed while the integration snapshot was being assembled; retry the export.",
                context.correlationId(), details));
    }

    static String draftFingerprint(GraphDraft draft) {
        return VisualBundleFingerprint.fromMaterial(Map.of("draft", draft.withNodeFixtures(Map.of())));
    }
}
