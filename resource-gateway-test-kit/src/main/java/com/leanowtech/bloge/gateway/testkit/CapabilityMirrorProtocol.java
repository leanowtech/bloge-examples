package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Public wire constants and the packaged compatibility baseline for capability-mirror clients.
 *
 * <p>The constants belong to the standalone test kit rather than the Resource Gateway server so a
 * governance consumer can negotiate and verify mirror artifacts without linking Spring or server
 * implementation classes.</p>
 */
public final class CapabilityMirrorProtocol {

    /** Tool Studio integration protocol name required by the Stage 0 baseline. */
    public static final String INTEGRATION_PROTOCOL = "ToolStudioResourceGatewayProtocol";
    /** Tool Studio integration protocol version required by the Stage 0 baseline. */
    public static final String INTEGRATION_PROTOCOL_V1 = "1.0.0";
    /** Capability-mirror compatibility fixture wire version. */
    public static final String COMPATIBILITY_V1 =
            "resourceGateway.capabilityMirrorCompatibility.v1";
    /** Artifact provenance wire version. */
    public static final String ARTIFACT_PROVENANCE_V1 =
            "resourceGateway.artifactProvenance.v1";
    /** Effect contract wire version. */
    public static final String EFFECT_CONTRACT_V1 = "resourceGateway.effectContract.v1";
    /** Capability contract wire version. */
    public static final String CAPABILITY_CONTRACT_V1 =
            "resourceGateway.capabilityContract.v1";
    /** Capability snapshot wire version. */
    public static final String CAPABILITY_SNAPSHOT_V1 =
            "resourceGateway.capabilitySnapshot.v1";
    /** Capability closure wire version. */
    public static final String CAPABILITY_CLOSURE_V1 =
            "resourceGateway.capabilityClosure.v1";
    /** Capability lifecycle transition wire version. */
    public static final String CAPABILITY_LIFECYCLE_TRANSITION_V1 =
            "resourceGateway.capabilityLifecycleTransition.v1";
    /** Protected mirror execution-command wire version. */
    public static final String MIRROR_EXECUTION_REQUEST_V1 =
            "resourceGateway.mirrorExecutionRequest.v1";
    /** Protected stateful mirror execution-command wire version. */
    public static final String MIRROR_EXECUTION_REQUEST_V2 =
            "resourceGateway.mirrorExecutionRequest.v2";
    /** Payload-free terminal mirror run-summary wire version. */
    public static final String MIRROR_RUN_SUMMARY_V1 =
            "resourceGateway.mirrorRunSummary.v1";
    /** Per-attempt mirror resolution wire version. */
    public static final String MIRROR_RESOLUTION_V1 = "resourceGateway.mirrorResolution.v1";
    /** Payload-free terminal mirror run evidence wire version. */
    public static final String MIRROR_RUN_EVIDENCE_V1 =
            "resourceGateway.mirrorRunEvidence.v1";
    /** Current payload-free evidence version carrying double-observed deployment trust. */
    public static final String MIRROR_RUN_EVIDENCE_V2 =
            "resourceGateway.mirrorRunEvidence.v2";
    /** Stateful payload-free evidence version carrying an exact Session access closure. */
    public static final String MIRROR_RUN_EVIDENCE_V3 =
            "resourceGateway.mirrorRunEvidence.v3";
    /** Read/write stateful evidence carrying an exact Session transition closure. */
    public static final String MIRROR_RUN_EVIDENCE_V4 =
            "resourceGateway.mirrorRunEvidence.v4";
    /** Failure-aware stateful evidence carrying every terminal write-attempt outcome. */
    public static final String MIRROR_RUN_EVIDENCE_V5 =
            "resourceGateway.mirrorRunEvidence.v5";
    /** Payload-free Session state-access evidence wire version. */
    public static final String MIRROR_STATE_RUN_EVIDENCE_V1 =
            "resourceGateway.mirrorStateRunEvidence.v1";
    /** Payload-free Session read/write transition evidence wire version. */
    public static final String MIRROR_STATE_RUN_EVIDENCE_V2 =
            "resourceGateway.mirrorStateRunEvidence.v2";
    /** Failure-aware Session write-attempt evidence wire version. */
    public static final String MIRROR_STATE_RUN_EVIDENCE_V3 =
            "resourceGateway.mirrorStateRunEvidence.v3";
    /** Deterministic ANEKE state-workbook seed wire version. */
    public static final String MIRROR_STATE_WORKBOOK_SEED_V1 =
            "resourceGateway.mirrorStateWorkbookSeed.v1";
    /** Deterministic ANEKE read/write state-transition workbook seed wire version. */
    public static final String MIRROR_STATE_TRANSITION_WORKBOOK_SEED_V1 =
            "resourceGateway.mirrorStateTransitionWorkbookSeed.v1";
    /** Deterministic ANEKE failure-aware state-write workbook seed wire version. */
    public static final String MIRROR_STATE_WRITE_OUTCOME_WORKBOOK_SEED_V1 =
            "resourceGateway.mirrorStateWriteOutcomeWorkbookSeed.v1";
    /** Detached mirror evidence attestation wire version. */
    public static final String MIRROR_EVIDENCE_ATTESTATION_V1 =
            "resourceGateway.mirrorEvidenceAttestation.v1";
    /** Current detached evidence attestation wire version. */
    public static final String MIRROR_EVIDENCE_ATTESTATION_V2 =
            "resourceGateway.mirrorEvidenceAttestation.v2";
    /** Stateful detached evidence attestation wire version. */
    public static final String MIRROR_EVIDENCE_ATTESTATION_V3 =
            "resourceGateway.mirrorEvidenceAttestation.v3";
    /** Read/write stateful detached evidence attestation wire version. */
    public static final String MIRROR_EVIDENCE_ATTESTATION_V4 =
            "resourceGateway.mirrorEvidenceAttestation.v4";
    /** Failure-aware stateful detached evidence attestation wire version. */
    public static final String MIRROR_EVIDENCE_ATTESTATION_V5 =
            "resourceGateway.mirrorEvidenceAttestation.v5";
    /** Portable signed mirror evidence bundle wire version. */
    public static final String MIRROR_EVIDENCE_BUNDLE_V1 =
            "resourceGateway.mirrorEvidenceBundle.v1";
    /** Current portable evidence bundle wire version. */
    public static final String MIRROR_EVIDENCE_BUNDLE_V2 =
            "resourceGateway.mirrorEvidenceBundle.v2";
    /** Stateful portable evidence bundle wire version. */
    public static final String MIRROR_EVIDENCE_BUNDLE_V3 =
            "resourceGateway.mirrorEvidenceBundle.v3";
    /** Read/write stateful portable evidence bundle wire version. */
    public static final String MIRROR_EVIDENCE_BUNDLE_V4 =
            "resourceGateway.mirrorEvidenceBundle.v4";
    /** Failure-aware stateful portable evidence bundle wire version. */
    public static final String MIRROR_EVIDENCE_BUNDLE_V5 =
            "resourceGateway.mirrorEvidenceBundle.v5";
    /** Externally signed deployment-isolation attestation wire version. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION_V1 =
            "resourceGateway.mirrorDeploymentIsolationAttestation.v1";
    /** Append-only local deployment-isolation attestation status wire version. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION_STATUS_V1 =
            "resourceGateway.mirrorDeploymentIsolationAttestationStatus.v1";
    /** Atomic deployment-isolation attestation and current-status bundle wire version. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION_BUNDLE_V1 =
            "resourceGateway.mirrorDeploymentIsolationAttestationBundle.v1";
    /** Atomic deployment-agent read-only cache snapshot wire version. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_AGENT_SNAPSHOT_V1 =
            "resourceGateway.mirrorDeploymentIsolationAgentSnapshot.v1";
    /** Double-observed deployment-isolation run-trust binding wire version. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_RUN_TRUST_V1 =
            "resourceGateway.mirrorDeploymentIsolationRunTrust.v1";
    /** Optimistically fenced irreversible attestation revocation command wire version. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION_REVOCATION_REQUEST_V1 =
            "resourceGateway.mirrorDeploymentIsolationAttestationRevocationRequest.v1";
    /** Signed deployment-isolation compatibility fixture version. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_COMPATIBILITY_V1 =
            "resourceGateway.mirrorDeploymentIsolationCompatibility.v1";
    /** Threshold-signed deployment-isolation authority key-set publication wire version. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_AUTHORITY_KEY_SET_V1 =
            "resourceGateway.mirrorDeploymentIsolationAuthorityKeySetPublication.v1";
    /** Threshold-signed read-only Shadow authority key-set publication wire version. */
    public static final String READ_ONLY_SHADOW_AUTHORITY_KEY_SET_PUBLICATION_V1 =
            "resourceGateway.readOnlyShadowAuthorityKeySetPublication.v1";
    /** Signed deployment-isolation authority key-set compatibility fixture version. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_AUTHORITY_KEY_SET_COMPATIBILITY_V1 =
            "resourceGateway.mirrorDeploymentIsolationAuthorityKeySetCompatibility.v1";
    /** Signed Stage 1 mirror evidence compatibility fixture version. */
    public static final String MIRROR_EVIDENCE_COMPATIBILITY_V1 =
            "resourceGateway.mirrorEvidenceCompatibility.v1";
    /** Signed capability-observation wire version. */
    public static final String CAPABILITY_OBSERVATION_V1 =
            "resourceGateway.capabilityObservation.v1";
    /** Immutable capability-observation admission wire version. */
    public static final String CAPABILITY_OBSERVATION_ADMISSION_V1 =
            "resourceGateway.capabilityObservationAdmission.v1";
    /** Atomic capability-observation receipt wire version. */
    public static final String CAPABILITY_OBSERVATION_RECEIPT_V1 =
            "resourceGateway.capabilityObservationReceipt.v1";
    /** Fixed observation compatibility-fixture wire version. */
    public static final String CAPABILITY_OBSERVATION_COMPATIBILITY_V1 =
            "resourceGateway.capabilityObservationCompatibility.v1";
    /** Terminal quarantine-review command wire version. */
    public static final String CAPABILITY_OBSERVATION_REVIEW_REQUEST_V1 =
            "resourceGateway.capabilityObservationReviewRequest.v1";
    /** Immutable terminal quarantine-review wire version. */
    public static final String CAPABILITY_OBSERVATION_REVIEW_V1 =
            "resourceGateway.capabilityObservationReview.v1";
    /** Immutable corpus-candidate command wire version. */
    public static final String CAPABILITY_CORPUS_CANDIDATE_REQUEST_V1 =
            "resourceGateway.capabilityCorpusCandidateRequest.v1";
    /** Immutable payload-free corpus revision wire version. */
    public static final String CAPABILITY_CORPUS_REVISION_V1 =
            "resourceGateway.capabilityCorpusRevision.v1";
    /** Owner-reviewed corpus-publication command wire version. */
    public static final String CAPABILITY_CORPUS_PUBLISH_REQUEST_V1 =
            "resourceGateway.capabilityCorpusPublishRequest.v1";
    /** Immutable serving-publication fact wire version. */
    public static final String CAPABILITY_CORPUS_PUBLICATION_V1 =
            "resourceGateway.capabilityCorpusPublication.v1";
    /** Owner-reviewed recorded-trajectory command wire version. */
    public static final String CAPABILITY_CORPUS_TRAJECTORY_PUBLISH_REQUEST_V1 =
            "resourceGateway.capabilityCorpusTrajectoryPublishRequest.v1";
    /** Immutable recorded-trajectory publication wire version. */
    public static final String CAPABILITY_CORPUS_TRAJECTORY_PUBLICATION_V1 =
            "resourceGateway.capabilityCorpusTrajectoryPublication.v1";
    /** Externally verified payload-free cluster-validation wire version. */
    public static final String CAPABILITY_CORPUS_CLUSTER_VALIDATION_V1 =
            "resourceGateway.capabilityCorpusClusterValidation.v1";
    /** Owner-reviewed recorded-cluster publication command wire version. */
    public static final String CAPABILITY_CORPUS_CLUSTER_PUBLISH_REQUEST_V1 =
            "resourceGateway.capabilityCorpusClusterPublishRequest.v1";
    /** Immutable recorded-cluster publication wire version. */
    public static final String CAPABILITY_CORPUS_CLUSTER_PUBLICATION_V1 =
            "resourceGateway.capabilityCorpusClusterPublication.v1";
    /** Fixed recorded-cluster compatibility-fixture wire version. */
    public static final String CAPABILITY_CORPUS_CLUSTER_COMPATIBILITY_V1 =
            "resourceGateway.capabilityCorpusClusterStage2Fixture.v1";
    /** Fixed corpus-governance compatibility-fixture wire version. */
    public static final String CAPABILITY_CORPUS_COMPATIBILITY_V1 =
            "resourceGateway.capabilityCorpusCompatibility.v1";
    /** Fixture metadata contract selecting exact corpus serving publications. */
    public static final String FIXTURE_MIRROR_CORPUS_BINDINGS_V1 =
            "resourceGateway.fixtureMirrorCorpusBindings.v1";
    /** Fixture metadata contract selecting exact reviewed retry trajectories. */
    public static final String FIXTURE_MIRROR_TRAJECTORY_BINDINGS_V1 =
            "resourceGateway.fixtureMirrorTrajectoryBindings.v1";
    /** Fixture metadata contract selecting exact reviewed recorded clusters. */
    public static final String FIXTURE_MIRROR_CLUSTER_BINDINGS_V1 =
            "resourceGateway.fixtureMirrorClusterBindings.v1";
    /** Bounded deterministic state-expression wire version. */
    public static final String BOUNDED_STATE_EXPRESSION_V1 =
            "resourceGateway.boundedStateExpression.v1";
    /** Governed state-model wire version. */
    public static final String STATE_MODEL_V1 =
            "resourceGateway.stateModel.v1";
    /** Governed session-state read-lowering wire version. */
    public static final String STATE_READ_SPEC_V1 =
            "resourceGateway.stateReadSpec.v1";
    /** Governed virtual write-effect wire version. */
    public static final String WRITE_EFFECT_SPEC_V1 =
            "resourceGateway.writeEffectSpec.v1";
    /** Payload-bearing isolated session-state wire version. */
    public static final String SESSION_STATE_SPACE_V1 =
            "resourceGateway.sessionStateSpace.v1";
    /** Encrypted stateful-mirror session aggregate wire version. */
    public static final String MIRROR_SESSION_PAYLOAD_V1 =
            "resourceGateway.mirrorSessionPayload.v1";
    /** Stateful-mirror session-create command wire version. */
    public static final String MIRROR_SESSION_CREATE_REQUEST_V1 =
            "resourceGateway.mirrorSessionCreateRequest.v1";
    /** Payload-free stateful-mirror session descriptor wire version. */
    public static final String MIRROR_SESSION_DESCRIPTOR_V1 =
            "resourceGateway.mirrorSessionDescriptor.v1";
    /** Stateful-mirror state-transition command wire version. */
    public static final String MIRROR_SESSION_COMMAND_REQUEST_V1 =
            "resourceGateway.mirrorSessionCommandRequest.v1";
    /** Stateful-mirror committed or replayed command-result wire version. */
    public static final String MIRROR_SESSION_COMMAND_RESULT_V1 =
            "resourceGateway.mirrorSessionCommandResult.v1";
    /** Immutable durable Session-store generation wire version. */
    public static final String MIRROR_SESSION_STORE_GENERATION_V1 =
            "resourceGateway.mirrorSessionStoreGeneration.v1";
    /** Payload-free exact Session checkpoint wire version. */
    public static final String MIRROR_SESSION_CHECKPOINT_V1 =
            "resourceGateway.mirrorSessionCheckpoint.v1";
    /** Detached checkpoint attestation wire version. */
    public static final String MIRROR_SESSION_CHECKPOINT_ATTESTATION_V1 =
            "resourceGateway.mirrorSessionCheckpointAttestation.v1";
    /** Portable signed checkpoint bundle wire version. */
    public static final String MIRROR_SESSION_CHECKPOINT_BUNDLE_V1 =
            "resourceGateway.mirrorSessionCheckpointBundle.v1";
    /** Successful exact Session recovery-admission result wire version. */
    public static final String MIRROR_SESSION_RECOVERY_RESULT_V1 =
            "resourceGateway.mirrorSessionRecoveryResult.v1";
    /** Governed payload-free scenario-pack wire version. */
    public static final String SCENARIO_PACK_V1 =
            "resourceGateway.scenarioPack.v1";
    /** Governed exact scenario-case wire version. */
    public static final String SCENARIO_CASE_V1 =
            "resourceGateway.scenarioCase.v1";
    /** Governed payload-free case handling assertion wire version. */
    public static final String CASE_HANDLING_ASSERTION_V1 =
            "resourceGateway.caseHandlingAssertion.v1";
    /** Compiled and governed Scenario rehearsal execution-plan wire version. */
    public static final String COMPILED_SCENARIO_REHEARSAL_PLAN_V1 =
            "resourceGateway.compiledScenarioRehearsalPlan.v1";
    /** Strict payload-free multi-plan Scenario batch request wire version. */
    public static final String SCENARIO_REHEARSAL_BATCH_REQUEST_V1 =
            "resourceGateway.scenarioRehearsalBatchRequest.v1";
    /** Immutable content-addressed Scenario batch execution closure wire version. */
    public static final String SCENARIO_REHEARSAL_BATCH_MANIFEST_V1 =
            "resourceGateway.scenarioRehearsalBatchManifest.v1";
    /** Idempotent cooperative Scenario batch cancellation command wire version. */
    public static final String SCENARIO_REHEARSAL_BATCH_CANCELLATION_REQUEST_V1 =
            "resourceGateway.scenarioRehearsalBatchCancellationRequest.v1";
    /** Durable payload-free Scenario batch job projection wire version. */
    public static final String SCENARIO_REHEARSAL_BATCH_JOB_V1 =
            "resourceGateway.scenarioRehearsalBatchJob.v1";
    /** Durable Scenario batch job projection with explicit evidence finalization. */
    public static final String SCENARIO_REHEARSAL_BATCH_JOB_V2 =
            "resourceGateway.scenarioRehearsalBatchJob.v2";
    /** Exact-scope keyset page over payload-free Scenario batch jobs. */
    public static final String SCENARIO_REHEARSAL_BATCH_JOB_PAGE_V1 =
            "resourceGateway.scenarioRehearsalBatchJobPage.v1";
    /** Payload-free batch evidence finalization status wire version. */
    public static final String
    SCENARIO_REHEARSAL_BATCH_FINALIZATION_STATUS_V1 =
            "resourceGateway.scenarioRehearsalBatchFinalizationStatus.v1";
    /** Compare-and-set quarantined batch-finalization remediation command wire version. */
    public static final String
    SCENARIO_REHEARSAL_BATCH_FINALIZATION_REMEDIATION_REQUEST_V1 =
            "resourceGateway.scenarioRehearsalBatchFinalizationRemediationRequest.v1";
    /** Immutable payload-free batch-finalization remediation receipt wire version. */
    public static final String
    SCENARIO_REHEARSAL_BATCH_FINALIZATION_REMEDIATION_RECEIPT_V1 =
            "resourceGateway.scenarioRehearsalBatchFinalizationRemediationReceipt.v1";
    /** Exact-scope payload-free batch-finalization aggregate health wire version. */
    public static final String
    SCENARIO_REHEARSAL_BATCH_FINALIZATION_HEALTH_V1 =
            "resourceGateway.scenarioRehearsalBatchFinalizationHealth.v1";
    /** Reviewed Scenario successor preview-command wire version. */
    public static final String
    SCENARIO_REHEARSAL_REMEDIATION_PREVIEW_REQUEST_V1 =
            "resourceGateway.scenarioRehearsalRemediationPreviewRequest.v1";
    /** Immutable frozen Scenario successor remediation plan wire version. */
    public static final String
    SCENARIO_REHEARSAL_REMEDIATION_PLAN_V1 =
            "resourceGateway.scenarioRehearsalRemediationPlan.v1";
    /** Role-bound append-only Scenario remediation approval command wire version. */
    public static final String
    SCENARIO_REHEARSAL_REMEDIATION_APPROVAL_COMMAND_V1 =
            "resourceGateway.scenarioRehearsalRemediationApprovalCommand.v1";
    /** Actor-bound append-only Scenario remediation approval fact wire version. */
    public static final String
    SCENARIO_REHEARSAL_REMEDIATION_APPROVAL_V1 =
            "resourceGateway.scenarioRehearsalRemediationApproval.v1";
    /** Approved Scenario remediation submit command wire version. */
    public static final String
    SCENARIO_REHEARSAL_REMEDIATION_SUBMIT_COMMAND_V1 =
            "resourceGateway.scenarioRehearsalRemediationSubmitCommand.v1";
    /** Immutable predecessor-to-successor remediation admission receipt wire version. */
    public static final String
    SCENARIO_REHEARSAL_REMEDIATION_RECEIPT_V1 =
            "resourceGateway.scenarioRehearsalRemediationReceipt.v1";
    /** Content-addressed complete reviewed-remediation lineage wire version. */
    public static final String
    SCENARIO_REHEARSAL_REMEDIATION_LINEAGE_V1 =
            "resourceGateway.scenarioRehearsalRemediationLineage.v1";
    /** Deterministic signed-workbook remediation comparison wire version. */
    public static final String
    SCENARIO_REHEARSAL_REMEDIATION_COMPARISON_V1 =
            "resourceGateway.scenarioRehearsalRemediationComparison.v1";
    /** Stable bounded Scenario batch item-page wire version. */
    public static final String SCENARIO_REHEARSAL_BATCH_ITEM_PAGE_V1 =
            "resourceGateway.scenarioRehearsalBatchItemPage.v1";
    /** Content-addressed terminal Scenario batch evidence index wire version. */
    public static final String SCENARIO_REHEARSAL_BATCH_EVIDENCE_INDEX_V1 =
            "resourceGateway.scenarioRehearsalBatchEvidenceIndex.v1";
    /** Content-addressed terminal Scenario batch evidence index v2. */
    public static final String SCENARIO_REHEARSAL_BATCH_EVIDENCE_INDEX_V2 =
            "resourceGateway.scenarioRehearsalBatchEvidenceIndex.v2";
    /** Detached terminal Scenario batch evidence attestation wire version. */
    public static final String
    SCENARIO_REHEARSAL_BATCH_EVIDENCE_ATTESTATION_V1 =
            "resourceGateway.scenarioRehearsalBatchEvidenceAttestation.v1";
    /** Detached terminal Scenario batch evidence attestation v2. */
    public static final String
    SCENARIO_REHEARSAL_BATCH_EVIDENCE_ATTESTATION_V2 =
            "resourceGateway.scenarioRehearsalBatchEvidenceAttestation.v2";
    /** Independently verifiable signed Scenario batch evidence wire version. */
    public static final String SCENARIO_REHEARSAL_BATCH_EVIDENCE_BUNDLE_V1 =
            "resourceGateway.scenarioRehearsalBatchEvidenceBundle.v1";
    /** Independently verifiable signed Scenario batch evidence v2. */
    public static final String SCENARIO_REHEARSAL_BATCH_EVIDENCE_BUNDLE_V2 =
            "resourceGateway.scenarioRehearsalBatchEvidenceBundle.v2";
    /** Independently verifiable signed Scenario rehearsal aggregate wire version. */
    public static final String SCENARIO_REHEARSAL_EVIDENCE_BUNDLE_V1 =
            "resourceGateway.scenarioRehearsalEvidenceBundle.v1";
    /** Idempotent Scenario evidence legal-hold command wire version. */
    public static final String SCENARIO_REHEARSAL_LEGAL_HOLD_COMMAND_V1 =
            "resourceGateway.scenarioRehearsalLegalHoldCommand.v1";
    /** Idempotent Scenario aggregate evidence purge command wire version. */
    public static final String SCENARIO_REHEARSAL_PURGE_COMMAND_V1 =
            "resourceGateway.scenarioRehearsalPurgeCommand.v1";
    /** Signed append-only Scenario evidence retention event wire version. */
    public static final String SCENARIO_REHEARSAL_RETENTION_EVENT_V1 =
            "resourceGateway.scenarioRehearsalRetentionEvent.v1";
    /** Rebuildable Scenario evidence retention projection wire version. */
    public static final String SCENARIO_REHEARSAL_RETENTION_STATE_V1 =
            "resourceGateway.scenarioRehearsalRetentionState.v1";
    /** Signed append-only Scenario batch retention event wire version. */
    public static final String SCENARIO_REHEARSAL_BATCH_RETENTION_EVENT_V1 =
            "resourceGateway.scenarioRehearsalBatchRetentionEvent.v1";
    /** Rebuildable Scenario batch retention projection wire version. */
    public static final String SCENARIO_REHEARSAL_BATCH_RETENTION_STATE_V1 =
            "resourceGateway.scenarioRehearsalBatchRetentionState.v1";
    /** Deterministic payload-free ANEKE Scenario correctness-workbook seed wire version. */
    public static final String SCENARIO_REHEARSAL_WORKBOOK_SEED_V1 =
            "resourceGateway.scenarioRehearsalWorkbookSeed.v1";
    /** Deterministic payload-free ANEKE Scenario batch correctness-workbook seed wire version. */
    public static final String SCENARIO_REHEARSAL_BATCH_WORKBOOK_SEED_V1 =
            "resourceGateway.scenarioRehearsalBatchWorkbookSeed.v1";
    /** Fixed scenario protocol compatibility-fixture wire version. */
    public static final String SCENARIO_PACK_COMPATIBILITY_V1 =
            "resourceGateway.scenarioPackCompatibility.v1";
    /** Owner-approved domain fidelity denominator wire version. */
    public static final String DOMAIN_FIDELITY_INVENTORY_V1 =
            "resourceGateway.domainFidelityInventory.v1";
    /** Signed payload-free domain fidelity vector wire version. */
    public static final String DOMAIN_FIDELITY_PROFILE_V1 =
            "resourceGateway.domainFidelityProfile.v1";
    /** Signed payload-free read-only Shadow comparison wire version. */
    public static final String READ_ONLY_SHADOW_COMPARISON_V1 =
            "resourceGateway.readOnlyShadowComparison.v1";
    /** Signed read-only Shadow comparison with exact policy and source-resolution closure. */
    public static final String READ_ONLY_SHADOW_COMPARISON_V2 =
            "resourceGateway.readOnlyShadowComparison.v2";
    /** Current signed Shadow comparison with complete online-authority closure. */
    public static final String READ_ONLY_SHADOW_COMPARISON_V3 =
            "resourceGateway.readOnlyShadowComparison.v3";
    /** Durable payload-free read-only Shadow job submission wire version. */
    public static final String READ_ONLY_SHADOW_JOB_REQUEST_V1 =
            "resourceGateway.readOnlyShadowJobRequest.v1";
    /** Durable payload-free read-only Shadow job projection wire version. */
    public static final String READ_ONLY_SHADOW_JOB_V1 =
            "resourceGateway.readOnlyShadowJob.v1";
    /** Append-only payload-free read-only Shadow job lifecycle fact wire version. */
    public static final String
    READ_ONLY_SHADOW_JOB_LIFECYCLE_EVENT_V1 =
            "resourceGateway.readOnlyShadowJobLifecycleEvent.v1";
    /** Bounded cursor page of read-only Shadow job lifecycle facts wire version. */
    public static final String
    READ_ONLY_SHADOW_JOB_LIFECYCLE_PAGE_V1 =
            "resourceGateway.readOnlyShadowJobLifecyclePage.v1";
    /** Fixed Stage 3 refund fixture envelope version. */
    public static final String STATEFUL_REFUND_FIXTURE_V1 =
            "resourceGateway.statefulRefundFixture.v1";

    /** Classpath root containing the authoritative mirror schemas and fixtures. */
    public static final String SCHEMA_RESOURCE_ROOT = "/schemas/resource-gateway-mirror/";
    /** Packaged Stage 0 compatibility fixture. */
    public static final String COMPATIBILITY_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-mirror-stage0-v1.fixture.json";
    /** Packaged signed Stage 1 mirror evidence compatibility fixture. */
    public static final String MIRROR_EVIDENCE_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-evidence-stage1-v1.fixture.json";
    /** Packaged signed deployment-isolation compatibility fixture. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-deployment-isolation-stage1-v1.fixture.json";
    /** Packaged threshold-signed deployment-isolation authority key-set fixture. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_AUTHORITY_KEY_SET_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "mirror-deployment-isolation-authority-key-set-stage1-v1.fixture.json";
    /** Packaged signed capability-observation compatibility fixture. */
    public static final String CAPABILITY_OBSERVATION_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-observation-stage2-v1.fixture.json";
    /** Packaged payload-free corpus-governance compatibility fixture. */
    public static final String CAPABILITY_CORPUS_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-corpus-stage2-v1.fixture.json";
    /** Packaged payload-free recorded-cluster compatibility fixture. */
    public static final String CAPABILITY_CORPUS_CLUSTER_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "capability-corpus-cluster-stage2-v1.fixture.json";
    /** Packaged fixed fixture-level corpus-binding example. */
    public static final String FIXTURE_MIRROR_CORPUS_BINDINGS_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "fixture-mirror-corpus-bindings-v1.fixture.json";
    /** Packaged fixed fixture-level trajectory-binding example. */
    public static final String
    FIXTURE_MIRROR_TRAJECTORY_BINDINGS_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "fixture-mirror-trajectory-bindings-v1.fixture.json";
    /** Packaged fixed fixture-level cluster-binding example. */
    public static final String
    FIXTURE_MIRROR_CLUSTER_BINDINGS_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "fixture-mirror-cluster-bindings-v1.fixture.json";
    /** Packaged fixed Stage 3 refund-domain fixture. */
    public static final String STATEFUL_REFUND_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "stateful-refund-stage3-v1.fixture.json";
    /** Packaged fixed ScenarioPack compatibility fixture. */
    public static final String SCENARIO_PACK_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "scenario-pack-stage7-v1.fixture.json";
    /** Packaged compatibility fixture schema. */
    public static final String COMPATIBILITY_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-mirror-compatibility-v1.schema.json";
    /** Packaged capability snapshot schema. */
    public static final String CAPABILITY_SNAPSHOT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-snapshot-v1.schema.json";
    /** Packaged capability closure schema. */
    public static final String CAPABILITY_CLOSURE_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-closure-v1.schema.json";
    /** Packaged protected mirror execution-command schema. */
    public static final String MIRROR_EXECUTION_REQUEST_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-execution-request-v1.schema.json";
    /** Packaged protected stateful mirror execution-command schema. */
    public static final String MIRROR_EXECUTION_REQUEST_V2_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-execution-request-v2.schema.json";
    /** Packaged payload-free terminal run-summary schema. */
    public static final String MIRROR_RUN_SUMMARY_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-run-summary-v1.schema.json";
    /** Packaged per-attempt mirror resolution schema. */
    public static final String MIRROR_RESOLUTION_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-resolution-v1.schema.json";
    /** Packaged payload-free mirror run evidence schema. */
    public static final String MIRROR_RUN_EVIDENCE_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-run-evidence-v1.schema.json";
    /** Packaged current mirror run evidence schema. */
    public static final String MIRROR_RUN_EVIDENCE_V2_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-run-evidence-v2.schema.json";
    /** Packaged stateful mirror run evidence schema. */
    public static final String MIRROR_RUN_EVIDENCE_V3_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-run-evidence-v3.schema.json";
    /** Packaged read/write stateful mirror run evidence schema. */
    public static final String MIRROR_RUN_EVIDENCE_V4_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-run-evidence-v4.schema.json";
    /** Packaged failure-aware stateful mirror run evidence schema. */
    public static final String MIRROR_RUN_EVIDENCE_V5_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-run-evidence-v5.schema.json";
    /** Packaged payload-free Session state-access evidence schema. */
    public static final String MIRROR_STATE_RUN_EVIDENCE_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-state-run-evidence-v1.schema.json";
    /** Packaged payload-free Session read/write transition evidence schema. */
    public static final String MIRROR_STATE_RUN_EVIDENCE_V2_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-state-run-evidence-v2.schema.json";
    /** Packaged failure-aware Session write-attempt evidence schema. */
    public static final String MIRROR_STATE_RUN_EVIDENCE_V3_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-state-run-evidence-v3.schema.json";
    /** Packaged deterministic ANEKE state-workbook seed schema. */
    public static final String MIRROR_STATE_WORKBOOK_SEED_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-state-workbook-seed-v1.schema.json";
    /** Packaged deterministic ANEKE state-transition workbook seed schema. */
    public static final String MIRROR_STATE_TRANSITION_WORKBOOK_SEED_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "mirror-state-transition-workbook-seed-v1.schema.json";
    /** Packaged failure-aware state-write workbook seed schema. */
    public static final String
    MIRROR_STATE_WRITE_OUTCOME_WORKBOOK_SEED_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "mirror-state-write-outcome-workbook-seed-v1.schema.json";
    /** Packaged detached mirror evidence attestation schema. */
    public static final String MIRROR_EVIDENCE_ATTESTATION_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-evidence-attestation-v1.schema.json";
    /** Packaged current detached mirror evidence attestation schema. */
    public static final String MIRROR_EVIDENCE_ATTESTATION_V2_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-evidence-attestation-v2.schema.json";
    /** Packaged stateful detached mirror evidence attestation schema. */
    public static final String MIRROR_EVIDENCE_ATTESTATION_V3_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-evidence-attestation-v3.schema.json";
    /** Packaged read/write stateful detached evidence attestation schema. */
    public static final String MIRROR_EVIDENCE_ATTESTATION_V4_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-evidence-attestation-v4.schema.json";
    /** Packaged failure-aware stateful detached evidence attestation schema. */
    public static final String MIRROR_EVIDENCE_ATTESTATION_V5_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-evidence-attestation-v5.schema.json";
    /** Packaged portable mirror evidence bundle schema. */
    public static final String MIRROR_EVIDENCE_BUNDLE_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-evidence-bundle-v1.schema.json";
    /** Packaged current portable mirror evidence bundle schema. */
    public static final String MIRROR_EVIDENCE_BUNDLE_V2_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-evidence-bundle-v2.schema.json";
    /** Packaged stateful portable mirror evidence bundle schema. */
    public static final String MIRROR_EVIDENCE_BUNDLE_V3_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-evidence-bundle-v3.schema.json";
    /** Packaged read/write stateful portable evidence bundle schema. */
    public static final String MIRROR_EVIDENCE_BUNDLE_V4_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-evidence-bundle-v4.schema.json";
    /** Packaged failure-aware stateful portable evidence bundle schema. */
    public static final String MIRROR_EVIDENCE_BUNDLE_V5_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-evidence-bundle-v5.schema.json";
    /** Packaged deployment-isolation attestation schema. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "mirror-deployment-isolation-attestation-v1.schema.json";
    /** Packaged append-only local attestation status schema. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION_STATUS_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "mirror-deployment-isolation-attestation-status-v1.schema.json";
    /** Packaged atomic attestation and current-status bundle schema. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION_BUNDLE_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "mirror-deployment-isolation-attestation-bundle-v1.schema.json";
    /** Packaged atomic deployment-agent read-only cache snapshot schema. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_AGENT_SNAPSHOT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "mirror-deployment-isolation-agent-snapshot-v1.schema.json";
    /** Packaged double-observed deployment-isolation run-trust schema. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_RUN_TRUST_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "mirror-deployment-isolation-run-trust-v1.schema.json";
    /** Packaged optimistic irreversible attestation revocation-command schema. */
    public static final String
    MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION_REVOCATION_REQUEST_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "mirror-deployment-isolation-attestation-revocation-request-v1.schema.json";
    /** Packaged threshold-signed deployment-isolation authority key-set schema. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_AUTHORITY_KEY_SET_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "mirror-deployment-isolation-authority-key-set-publication-v1.schema.json";
    /** Packaged threshold-signed read-only Shadow authority key-set schema. */
    public static final String READ_ONLY_SHADOW_AUTHORITY_KEY_SET_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "read-only-shadow-authority-key-set-publication-v1.schema.json";
    /** Packaged signed read-only Shadow shared guard-policy schema. */
    public static final String READ_ONLY_SHADOW_GUARD_POLICY_PUBLICATION_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "read-only-shadow-guard-policy-publication-v1.schema.json";
    /** Packaged signed read-only Shadow sampling-grant schema. */
    public static final String READ_ONLY_SHADOW_SAMPLING_GRANT_PUBLICATION_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "read-only-shadow-sampling-grant-publication-v1.schema.json";
    /** Packaged signed read-only Shadow kill-switch schema. */
    public static final String READ_ONLY_SHADOW_KILL_SWITCH_PUBLICATION_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "read-only-shadow-kill-switch-publication-v1.schema.json";
    /** Packaged signed capability-observation schema. */
    public static final String CAPABILITY_OBSERVATION_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-observation-v1.schema.json";
    /** Packaged immutable capability-observation admission schema. */
    public static final String CAPABILITY_OBSERVATION_ADMISSION_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-observation-admission-v1.schema.json";
    /** Packaged atomic capability-observation receipt schema. */
    public static final String CAPABILITY_OBSERVATION_RECEIPT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-observation-receipt-v1.schema.json";
    /** Packaged terminal quarantine-review command schema. */
    public static final String CAPABILITY_OBSERVATION_REVIEW_REQUEST_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "capability-observation-review-request-v1.schema.json";
    /** Packaged immutable terminal quarantine-review schema. */
    public static final String CAPABILITY_OBSERVATION_REVIEW_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-observation-review-v1.schema.json";
    /** Packaged immutable corpus-candidate command schema. */
    public static final String CAPABILITY_CORPUS_CANDIDATE_REQUEST_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "capability-corpus-candidate-request-v1.schema.json";
    /** Packaged immutable payload-free corpus revision schema. */
    public static final String CAPABILITY_CORPUS_REVISION_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-corpus-revision-v1.schema.json";
    /** Packaged owner-reviewed corpus-publication command schema. */
    public static final String CAPABILITY_CORPUS_PUBLISH_REQUEST_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "capability-corpus-publish-request-v1.schema.json";
    /** Packaged immutable serving-publication fact schema. */
    public static final String CAPABILITY_CORPUS_PUBLICATION_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-corpus-publication-v1.schema.json";
    /** Packaged owner-reviewed recorded-trajectory command schema. */
    public static final String
    CAPABILITY_CORPUS_TRAJECTORY_PUBLISH_REQUEST_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "capability-corpus-trajectory-publish-request-v1.schema.json";
    /** Packaged immutable recorded-trajectory publication schema. */
    public static final String
    CAPABILITY_CORPUS_TRAJECTORY_PUBLICATION_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "capability-corpus-trajectory-publication-v1.schema.json";
    /** Packaged externally verified cluster-validation schema. */
    public static final String
    CAPABILITY_CORPUS_CLUSTER_VALIDATION_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "capability-corpus-cluster-validation-v1.schema.json";
    /** Packaged owner-reviewed recorded-cluster command schema. */
    public static final String
    CAPABILITY_CORPUS_CLUSTER_PUBLISH_REQUEST_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "capability-corpus-cluster-publish-request-v1.schema.json";
    /** Packaged immutable recorded-cluster publication schema. */
    public static final String
    CAPABILITY_CORPUS_CLUSTER_PUBLICATION_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "capability-corpus-cluster-publication-v1.schema.json";
    /** Packaged strict fixture-level corpus-binding schema. */
    public static final String FIXTURE_MIRROR_CORPUS_BINDINGS_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "fixture-mirror-corpus-bindings-v1.schema.json";
    /** Packaged strict fixture-level trajectory-binding schema. */
    public static final String
    FIXTURE_MIRROR_TRAJECTORY_BINDINGS_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "fixture-mirror-trajectory-bindings-v1.schema.json";
    /** Packaged strict fixture-level cluster-binding schema. */
    public static final String
    FIXTURE_MIRROR_CLUSTER_BINDINGS_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "fixture-mirror-cluster-bindings-v1.schema.json";
    /** Packaged bounded state-expression schema. */
    public static final String BOUNDED_STATE_EXPRESSION_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "bounded-state-expression-v1.schema.json";
    /** Packaged governed state-model schema. */
    public static final String STATE_MODEL_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "state-model-v1.schema.json";
    /** Packaged governed session-state read-lowering schema. */
    public static final String STATE_READ_SPEC_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "state-read-spec-v1.schema.json";
    /** Packaged governed virtual write-effect schema. */
    public static final String WRITE_EFFECT_SPEC_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "write-effect-spec-v1.schema.json";
    /** Packaged isolated session-state schema. */
    public static final String SESSION_STATE_SPACE_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "session-state-space-v1.schema.json";
    /** Packaged encrypted stateful-mirror session aggregate schema. */
    public static final String MIRROR_SESSION_PAYLOAD_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-session-payload-v1.schema.json";
    /** Packaged stateful-mirror session-create command schema. */
    public static final String MIRROR_SESSION_CREATE_REQUEST_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-session-create-request-v1.schema.json";
    /** Packaged payload-free stateful-mirror session descriptor schema. */
    public static final String MIRROR_SESSION_DESCRIPTOR_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-session-descriptor-v1.schema.json";
    /** Packaged stateful-mirror state-transition command schema. */
    public static final String MIRROR_SESSION_COMMAND_REQUEST_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-session-command-request-v1.schema.json";
    /** Packaged stateful-mirror command-result schema. */
    public static final String MIRROR_SESSION_COMMAND_RESULT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-session-command-result-v1.schema.json";
    /** Packaged immutable Session-store generation schema. */
    public static final String MIRROR_SESSION_STORE_GENERATION_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "mirror-session-store-generation-v1.schema.json";
    /** Packaged durable payload-free Session write-attempt schema. */
    public static final String MIRROR_STATE_WRITE_ATTEMPT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "mirror-state-write-attempt-v1.schema.json";
    /** Packaged payload-free exact Session checkpoint schema. */
    public static final String MIRROR_SESSION_CHECKPOINT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "mirror-session-checkpoint-v1.schema.json";
    /** Packaged detached checkpoint attestation schema. */
    public static final String MIRROR_SESSION_CHECKPOINT_ATTESTATION_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "mirror-session-checkpoint-attestation-v1.schema.json";
    /** Packaged portable signed checkpoint bundle schema. */
    public static final String MIRROR_SESSION_CHECKPOINT_BUNDLE_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "mirror-session-checkpoint-bundle-v1.schema.json";
    /** Packaged successful Session recovery result schema. */
    public static final String MIRROR_SESSION_RECOVERY_RESULT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "mirror-session-recovery-result-v1.schema.json";
    /** Packaged governed scenario-pack schema. */
    public static final String SCENARIO_PACK_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "scenario-pack-v1.schema.json";
    /** Packaged governed scenario-case schema. */
    public static final String SCENARIO_CASE_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "scenario-case-v1.schema.json";
    /** Packaged governed case handling assertion schema. */
    public static final String CASE_HANDLING_ASSERTION_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "case-handling-assertion-v1.schema.json";
    /** Packaged payload-free result of evaluating one exact handling assertion. */
    public static final String SCENARIO_HANDLING_ASSERTION_RESULT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-handling-assertion-result-v1.schema.json";
    /** Packaged compiled and governed Scenario rehearsal execution plan schema. */
    public static final String COMPILED_SCENARIO_REHEARSAL_PLAN_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "compiled-scenario-rehearsal-plan-v1.schema.json";
    /** Packaged payload-free command for one exact compiled Scenario rehearsal. */
    public static final String SCENARIO_REHEARSAL_EXECUTION_REQUEST_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-execution-request-v1.schema.json";
    /** Packaged strict payload-free multi-plan Scenario batch request. */
    public static final String SCENARIO_REHEARSAL_BATCH_REQUEST_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-batch-request-v1.schema.json";
    /** Packaged immutable content-addressed Scenario batch execution closure. */
    public static final String SCENARIO_REHEARSAL_BATCH_MANIFEST_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-batch-manifest-v1.schema.json";
    /** Packaged idempotent cooperative Scenario batch cancellation command. */
    public static final String
    SCENARIO_REHEARSAL_BATCH_CANCELLATION_REQUEST_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-batch-cancellation-request-v1.schema.json";
    /** Packaged legacy durable Scenario batch job projection. */
    public static final String SCENARIO_REHEARSAL_BATCH_JOB_V1_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-batch-job-v1.schema.json";
    /** Packaged current durable Scenario batch job projection. */
    public static final String SCENARIO_REHEARSAL_BATCH_JOB_V2_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-batch-job-v2.schema.json";
    /** Packaged current durable Scenario batch job projection. */
    public static final String SCENARIO_REHEARSAL_BATCH_JOB_SCHEMA_RESOURCE =
            SCENARIO_REHEARSAL_BATCH_JOB_V2_SCHEMA_RESOURCE;
    /** Packaged exact-scope keyset page over Scenario batch jobs. */
    public static final String SCENARIO_REHEARSAL_BATCH_JOB_PAGE_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-batch-job-page-v1.schema.json";
    /** Packaged payload-free batch evidence finalization status. */
    public static final String
    SCENARIO_REHEARSAL_BATCH_FINALIZATION_STATUS_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-batch-finalization-status-v1.schema.json";
    /** Packaged compare-and-set quarantined finalization remediation command. */
    public static final String
    SCENARIO_REHEARSAL_BATCH_FINALIZATION_REMEDIATION_REQUEST_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-batch-finalization-remediation-request-v1.schema.json";
    /** Packaged immutable payload-free finalization remediation receipt. */
    public static final String
    SCENARIO_REHEARSAL_BATCH_FINALIZATION_REMEDIATION_RECEIPT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-batch-finalization-remediation-receipt-v1.schema.json";
    /** Packaged exact-scope payload-free finalization aggregate health. */
    public static final String
    SCENARIO_REHEARSAL_BATCH_FINALIZATION_HEALTH_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-batch-finalization-health-v1.schema.json";
    /** Packaged reviewed Scenario successor preview command. */
    public static final String
    SCENARIO_REHEARSAL_REMEDIATION_PREVIEW_REQUEST_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-remediation-preview-request-v1.schema.json";
    /** Packaged immutable frozen Scenario successor remediation plan. */
    public static final String
    SCENARIO_REHEARSAL_REMEDIATION_PLAN_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-remediation-plan-v1.schema.json";
    /** Packaged role-bound append-only Scenario remediation approval command. */
    public static final String
    SCENARIO_REHEARSAL_REMEDIATION_APPROVAL_COMMAND_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-remediation-approval-command-v1.schema.json";
    /** Packaged actor-bound append-only Scenario remediation approval fact. */
    public static final String
    SCENARIO_REHEARSAL_REMEDIATION_APPROVAL_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-remediation-approval-v1.schema.json";
    /** Packaged approved Scenario remediation submit command. */
    public static final String
    SCENARIO_REHEARSAL_REMEDIATION_SUBMIT_COMMAND_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-remediation-submit-command-v1.schema.json";
    /** Packaged immutable predecessor-to-successor remediation admission receipt. */
    public static final String
    SCENARIO_REHEARSAL_REMEDIATION_RECEIPT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-remediation-receipt-v1.schema.json";
    /** Packaged content-addressed complete reviewed-remediation lineage. */
    public static final String
    SCENARIO_REHEARSAL_REMEDIATION_LINEAGE_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-remediation-lineage-v1.schema.json";
    /** Packaged deterministic signed-workbook remediation comparison. */
    public static final String
    SCENARIO_REHEARSAL_REMEDIATION_COMPARISON_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-remediation-comparison-v1.schema.json";
    /** Packaged owner-approved domain fidelity denominator schema. */
    public static final String DOMAIN_FIDELITY_INVENTORY_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "domain-fidelity-inventory-v1.schema.json";
    /** Packaged strict owner command for one denominator revision. */
    public static final String
    DOMAIN_FIDELITY_INVENTORY_REGISTRATION_REQUEST_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "domain-fidelity-inventory-registration-request-v1.schema.json";
    /** Packaged signed payload-free domain fidelity vector schema. */
    public static final String DOMAIN_FIDELITY_PROFILE_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "domain-fidelity-profile-v1.schema.json";
    /** Packaged signed payload-free read-only Shadow comparison schema. */
    public static final String
    READ_ONLY_SHADOW_COMPARISON_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "read-only-shadow-comparison-v1.schema.json";
    /** Packaged v2 Shadow comparison schema with policy and source-resolution closure. */
    public static final String
    READ_ONLY_SHADOW_COMPARISON_V2_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "read-only-shadow-comparison-v2.schema.json";
    /** Packaged v3 Shadow comparison schema with double-observed online-authority closure. */
    public static final String
    READ_ONLY_SHADOW_COMPARISON_V3_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "read-only-shadow-comparison-v3.schema.json";
    /** Packaged durable read-only Shadow job submission schema. */
    public static final String
    READ_ONLY_SHADOW_JOB_REQUEST_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "read-only-shadow-job-request-v1.schema.json";
    /** Packaged durable read-only Shadow job projection schema. */
    public static final String
    READ_ONLY_SHADOW_JOB_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "read-only-shadow-job-v1.schema.json";
    /** Packaged append-only read-only Shadow job lifecycle event schema. */
    public static final String
    READ_ONLY_SHADOW_JOB_LIFECYCLE_EVENT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "read-only-shadow-job-lifecycle-event-v1.schema.json";
    /** Packaged bounded read-only Shadow job lifecycle page schema. */
    public static final String
    READ_ONLY_SHADOW_JOB_LIFECYCLE_PAGE_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "read-only-shadow-job-lifecycle-page-v1.schema.json";
    /** Packaged stable bounded Scenario batch item page. */
    public static final String SCENARIO_REHEARSAL_BATCH_ITEM_PAGE_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-batch-item-page-v1.schema.json";
    /** Packaged legacy content-addressed terminal Scenario batch evidence index. */
    public static final String
    SCENARIO_REHEARSAL_BATCH_EVIDENCE_INDEX_V1_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-batch-evidence-index-v1.schema.json";
    /** Packaged current content-addressed terminal Scenario batch evidence index. */
    public static final String
    SCENARIO_REHEARSAL_BATCH_EVIDENCE_INDEX_V2_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-batch-evidence-index-v2.schema.json";
    /** Packaged current content-addressed terminal Scenario batch evidence index. */
    public static final String
    SCENARIO_REHEARSAL_BATCH_EVIDENCE_INDEX_SCHEMA_RESOURCE =
            SCENARIO_REHEARSAL_BATCH_EVIDENCE_INDEX_V2_SCHEMA_RESOURCE;
    /** Packaged legacy detached terminal Scenario batch evidence attestation. */
    public static final String
    SCENARIO_REHEARSAL_BATCH_EVIDENCE_ATTESTATION_V1_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-batch-evidence-attestation-v1.schema.json";
    /** Packaged current detached terminal Scenario batch evidence attestation. */
    public static final String
    SCENARIO_REHEARSAL_BATCH_EVIDENCE_ATTESTATION_V2_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-batch-evidence-attestation-v2.schema.json";
    /** Packaged current detached terminal Scenario batch evidence attestation. */
    public static final String
    SCENARIO_REHEARSAL_BATCH_EVIDENCE_ATTESTATION_SCHEMA_RESOURCE =
            SCENARIO_REHEARSAL_BATCH_EVIDENCE_ATTESTATION_V2_SCHEMA_RESOURCE;
    /** Packaged legacy independently verifiable signed Scenario batch evidence. */
    public static final String
    SCENARIO_REHEARSAL_BATCH_EVIDENCE_BUNDLE_V1_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-batch-evidence-bundle-v1.schema.json";
    /** Packaged current independently verifiable signed Scenario batch evidence. */
    public static final String
    SCENARIO_REHEARSAL_BATCH_EVIDENCE_BUNDLE_V2_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-batch-evidence-bundle-v2.schema.json";
    /** Packaged current independently verifiable signed Scenario batch evidence. */
    public static final String
    SCENARIO_REHEARSAL_BATCH_EVIDENCE_BUNDLE_SCHEMA_RESOURCE =
            SCENARIO_REHEARSAL_BATCH_EVIDENCE_BUNDLE_V2_SCHEMA_RESOURCE;
    /** Packaged payload-free result for one compiled Scenario case. */
    public static final String SCENARIO_CASE_REHEARSAL_RESULT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-case-rehearsal-result-v1.schema.json";
    /** Packaged payload-free aggregate over one complete Scenario rehearsal. */
    public static final String SCENARIO_REHEARSAL_RESULT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-result-v1.schema.json";
    /** Packaged detached signature manifest for one Scenario aggregate. */
    public static final String SCENARIO_REHEARSAL_EVIDENCE_ATTESTATION_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-evidence-attestation-v1.schema.json";
    /** Packaged independently verifiable payload-free Scenario evidence bundle. */
    public static final String SCENARIO_REHEARSAL_EVIDENCE_BUNDLE_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-evidence-bundle-v1.schema.json";
    /** Packaged idempotent Scenario legal-hold command schema. */
    public static final String
    SCENARIO_REHEARSAL_LEGAL_HOLD_COMMAND_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-legal-hold-command-v1.schema.json";
    /** Packaged idempotent Scenario aggregate purge command schema. */
    public static final String SCENARIO_REHEARSAL_PURGE_COMMAND_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-purge-command-v1.schema.json";
    /** Packaged signed append-only Scenario retention event schema. */
    public static final String SCENARIO_REHEARSAL_RETENTION_EVENT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-retention-event-v1.schema.json";
    /** Packaged rebuildable Scenario retention projection schema. */
    public static final String SCENARIO_REHEARSAL_RETENTION_STATE_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-retention-state-v1.schema.json";
    /** Packaged signed append-only Scenario batch retention event schema. */
    public static final String
    SCENARIO_REHEARSAL_BATCH_RETENTION_EVENT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-batch-retention-event-v1.schema.json";
    /** Packaged rebuildable Scenario batch retention projection schema. */
    public static final String
    SCENARIO_REHEARSAL_BATCH_RETENTION_STATE_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-batch-retention-state-v1.schema.json";
    /** Packaged deterministic Scenario correctness-workbook seed schema. */
    public static final String SCENARIO_REHEARSAL_WORKBOOK_SEED_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-workbook-seed-v1.schema.json";
    /** Packaged deterministic Scenario batch correctness-workbook seed schema. */
    public static final String
    SCENARIO_REHEARSAL_BATCH_WORKBOOK_SEED_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-rehearsal-batch-workbook-seed-v1.schema.json";
    /** Packaged fixed ScenarioPack compatibility-fixture schema. */
    public static final String SCENARIO_PACK_FIXTURE_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "scenario-pack-stage7-v1.fixture.schema.json";
    /** Packaged fixed Stage 3 refund-fixture schema. */
    public static final String STATEFUL_REFUND_FIXTURE_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "stateful-refund-stage3-v1.fixture.schema.json";

    private static final ObjectMapper JSON = new ObjectMapper();

    private CapabilityMirrorProtocol() {
    }

    /**
     * Returns an independent copy of the machine-readable Stage 0 compatibility baseline.
     *
     * <p>The fixture is validated against its packaged strict JSON Schema before it is exposed. A
     * deep copy prevents one caller from changing the process-wide baseline seen by another.</p>
     *
     * @return validated mutable copy of the packaged compatibility fixture
     * @throws IllegalStateException when the test-kit artifact is incomplete or corrupt
     */
    public static JsonNode compatibilityBaseline() {
        return BaselineHolder.BASELINE.deepCopy();
    }

    /**
     * Returns the fixed, independently verified Stage 1 evidence compatibility fixture.
     *
     * <p>Consumers can run this fixture in packaging, upgrade, and startup probes to prove that
     * their canonicalization, closure checks, public-key parsing, and Ed25519 provider remain
     * compatible with the Resource Gateway producer contract.</p>
     *
     * @return detached signed bundle and immutable public verification key
     * @throws IllegalStateException when the packaged fixture is absent, malformed, or unverifiable
     */
    public static MirrorEvidenceCompatibilityFixture mirrorEvidenceCompatibilityFixture() {
        return MirrorFixtureHolder.FIXTURE.detachedCopy();
    }

    /**
     * Returns the fixed independently verified deployment-isolation compatibility fixture.
     *
     * <p>The fixture proves strict-schema loading, canonical nested fingerprints, immutable local
     * identity comparison, validity-window handling, public-key parsing, and Ed25519 verification
     * without contacting a Resource Gateway service.</p>
     *
     * @return detached signed attestation, pinned authority key, and expected execution window
     * @throws IllegalStateException when the packaged fixture is absent, malformed, or unverifiable
     */
    public static MirrorDeploymentIsolationCompatibilityFixture
    mirrorDeploymentIsolationCompatibilityFixture() {
        return IsolationFixtureHolder.FIXTURE.detachedCopy();
    }

    /**
     * Returns the fixed public-only isolation-authority key-set compatibility fixture.
     *
     * <p>The fixture proves strict-schema loading, nested canonical fingerprints, exact full-scope
     * binding, M-of-N public-root verification, and bootstrap generation handling without a server
     * process or any private key.</p>
     *
     * @return detached publication, local binding, bootstrap roots, and verification time
     * @throws IllegalStateException when the packaged fixture is absent, malformed, or unverifiable
     */
    public static MirrorDeploymentIsolationAuthorityKeySetCompatibilityFixture
    mirrorDeploymentIsolationAuthorityKeySetCompatibilityFixture() {
        return IsolationAuthorityFixtureHolder.FIXTURE.detachedCopy();
    }

    /**
     * Returns the fixed independently verified capability-observation fixture.
     *
     * <p>The fixture proves strict-schema loading, canonical content addressing, full-scope
     * comparison, purpose-window checks, public-key parsing, and Ed25519 verification without
     * contacting Resource Gateway or a payload vault.</p>
     *
     * @return detached signed observation, public key, expected scope, and verification time
     * @throws IllegalStateException when the packaged fixture is absent or unverifiable
     */
    public static CapabilityObservationCompatibilityFixture
            capabilityObservationCompatibilityFixture() {
        return ObservationFixtureHolder.FIXTURE.detachedCopy();
    }

    /**
     * Returns the fixed independently verified corpus-governance fixture.
     *
     * <p>The fixture proves strict-schema loading, canonical command and artifact fingerprints,
     * complete-scope closure, command-to-fact binding, policy-independent risk statistics,
     * lineage, and use horizons without linking Resource Gateway or contacting payload and policy
     * authorities.</p>
     *
     * @return detached payload-free review, candidate, and publication lifecycle
     * @throws IllegalStateException when the packaged fixture is absent or unverifiable
     */
    public static CapabilityCorpusCompatibilityFixture
            capabilityCorpusCompatibilityFixture() {
        return CorpusFixtureHolder.FIXTURE.detachedCopy();
    }

    /**
     * Returns the fixed independently verified recorded-cluster fixture.
     *
     * <p>The fixture proves strict schemas, canonical fingerprints, exact corpus membership,
     * identity-safe projection structure, holdout arithmetic, and Wilson precision confidence
     * without linking Resource Gateway or contacting a payload vault.</p>
     *
     * @return detached payload-free cluster publication lifecycle
     * @throws IllegalStateException when the packaged fixture is absent or unverifiable
     */
    public static CapabilityCorpusClusterCompatibilityFixture
            capabilityCorpusClusterCompatibilityFixture() {
        return ClusterFixtureHolder.FIXTURE.detachedCopy();
    }

    /**
     * Returns a strict-schema-verified copy of the fixed cluster-binding example.
     *
     * @return mutable copy of the nested {@code mirrorClusters} object
     * @throws IllegalStateException when the packaged fixture is absent or malformed
     */
    public static JsonNode fixtureMirrorClusterBindingsFixture() {
        return ClusterBindingFixtureHolder.FIXTURE.deepCopy();
    }

    /**
     * Returns the strict-schema and independently verified Stage 3 refund fixture.
     *
     * <p>The fixture includes an exact state model, one state-backed read, a two-entity atomic
     * write effect, a sealed initial session, and executable read/write expectations. Loading
     * proves the model/read/effect/session fingerprint closure without linking Resource Gateway
     * server classes.</p>
     *
     * @return mutable detached copy of the verified fixture
     * @throws IllegalStateException when the packaged fixture is absent or unverifiable
     */
    public static JsonNode statefulRefundFixture() {
        return StatefulRefundFixtureHolder.FIXTURE.deepCopy();
    }

    /**
     * Returns the strict-schema and independently verified ScenarioPack compatibility fixture.
     *
     * <p>The fixture is payload-free and closes one pack, one existing-test binding case, one
     * handling assertion, an explicit verification instant, and the expected bounded projection.
     * Loading proves producer/test-kit canonicalization and closure agreement without linking the
     * Resource Gateway server.</p>
     *
     * @return mutable detached copy of the verified scenario fixture
     * @throws IllegalStateException when the packaged fixture is absent or unverifiable
     */
    public static JsonNode scenarioPackCompatibilityFixture() {
        return ScenarioPackFixtureHolder.FIXTURE.deepCopy();
    }

    private static final class ScenarioPackFixtureHolder {
        private static final JsonNode FIXTURE = load();

        private static JsonNode load() {
            try (InputStream input = CapabilityMirrorProtocol.class.getResourceAsStream(
                    SCENARIO_PACK_FIXTURE_RESOURCE)) {
                if (input == null) {
                    throw new IOException("ScenarioPack fixture is absent");
                }
                JsonNode fixture = JSON.readTree(input);
                CapabilityMirrorSchemaValidator.require(
                        fixture,
                        SCENARIO_PACK_FIXTURE_SCHEMA_RESOURCE,
                        "RG.MIRROR.CLIENT.SCENARIO_FIXTURE_SCHEMA_INVALID");
                ScenarioPackVerifier.VerifiedScenarioPack verified =
                        new ScenarioPackVerifier().verify(
                                fixture.path("scenarioPack"),
                                toList(fixture.path("scenarioCases")),
                                toList(fixture.path("handlingAssertions")),
                                Instant.parse(fixture.path("verifiedAt").asText()));
                JsonNode expected = fixture.path("expected");
                if (!expected.path("packId").asText().equals(verified.packId())
                        || expected.path("revision").asLong() != verified.revision()
                        || !expected.path("fingerprint").asText()
                        .equals(verified.fingerprint())
                        || !expected.path("targetCapabilityId").asText()
                        .equals(verified.targetCapabilityId())
                        || !expected.path("caseIds").equals(
                        JSON.valueToTree(verified.caseIds()))
                        || !expected.path("caseTypes").equals(
                        JSON.valueToTree(verified.caseTypes()))
                        || expected.path("assertionCount").asInt()
                        != verified.assertionCount()
                        || expected.path("statefulCaseCount").asInt()
                        != verified.statefulCaseCount()
                        || expected.path("faultCaseCount").asInt()
                        != verified.faultCaseCount()
                        || expected.path("certificationRequired").asBoolean()
                        != verified.certificationRequired()) {
                    throw new IllegalArgumentException(
                            "ScenarioPack fixture expectation mismatch");
                }
                return fixture;
            } catch (IOException | RuntimeException failure) {
                throw new IllegalStateException(
                        "RG.MIRROR.CLIENT.SCENARIO_FIXTURE_UNAVAILABLE");
            }
        }
    }

    private static final class StatefulRefundFixtureHolder {
        private static final JsonNode FIXTURE = load();

        private static JsonNode load() {
            try (InputStream input = CapabilityMirrorProtocol.class.getResourceAsStream(
                    STATEFUL_REFUND_FIXTURE_RESOURCE)) {
                if (input == null) {
                    throw new IOException("Stateful refund fixture is absent");
                }
                JsonNode fixture = JSON.readTree(input);
                CapabilityMirrorSchemaValidator.require(
                        fixture,
                        STATEFUL_REFUND_FIXTURE_SCHEMA_RESOURCE,
                        "RG.MIRROR.CLIENT.STATEFUL_REFUND_FIXTURE_SCHEMA_INVALID");
                MirrorStateProtocolVerifier verifier = new MirrorStateProtocolVerifier();
                JsonNode model = fixture.path("stateModel");
                JsonNode readSpec = fixture.path("stateReadSpec");
                JsonNode effect = fixture.path("writeEffect");
                verifier.verifyStateModel(model);
                verifier.verifyStateReadSpec(readSpec, model);
                verifier.verifyWriteEffect(effect, model);
                verifier.verifySession(
                        fixture.path("initialState"), model, List.of(effect));
                return fixture;
            } catch (IOException | RuntimeException failure) {
                throw new IllegalStateException(
                        "RG.MIRROR.CLIENT.STATEFUL_REFUND_FIXTURE_UNAVAILABLE");
            }
        }
    }

    private static List<JsonNode> toList(JsonNode values) {
        if (values == null || !values.isArray()) {
            return List.of();
        }
        java.util.ArrayList<JsonNode> result = new java.util.ArrayList<>();
        values.forEach(result::add);
        return List.copyOf(result);
    }

    private static final class BaselineHolder {
        private static final JsonNode BASELINE = load();

        private static JsonNode load() {
            try (InputStream input = CapabilityMirrorProtocol.class.getResourceAsStream(
                    COMPATIBILITY_FIXTURE_RESOURCE)) {
                if (input == null) {
                    throw new IOException("Compatibility fixture is absent");
                }
                JsonNode baseline = JSON.readTree(input);
                CapabilityMirrorSchemaValidator.require(baseline, COMPATIBILITY_SCHEMA_RESOURCE,
                        "RG.MIRROR.CLIENT.COMPATIBILITY_BASELINE_INVALID");
                return baseline;
            } catch (IOException | RuntimeException failure) {
                throw new IllegalStateException(
                        "RG.MIRROR.CLIENT.COMPATIBILITY_BASELINE_UNAVAILABLE");
            }
        }
    }

    private static final class MirrorFixtureHolder {
        private static final MirrorEvidenceCompatibilityFixture FIXTURE = load();

        private static MirrorEvidenceCompatibilityFixture load() {
            try (InputStream input = CapabilityMirrorProtocol.class.getResourceAsStream(
                    MIRROR_EVIDENCE_FIXTURE_RESOURCE)) {
                if (input == null) {
                    throw new IOException("Mirror evidence fixture is absent");
                }
                JsonNode value = JSON.readTree(input);
                if (!value.isObject() || value.size() != 3
                        || !Set.of("schemaVersion", "verificationKey", "bundle")
                        .equals(fieldNames(value))
                        || !MIRROR_EVIDENCE_COMPATIBILITY_V1.equals(
                        value.path("schemaVersion").asText())) {
                    throw new IOException("Mirror evidence fixture envelope is invalid");
                }
                JsonNode keyValue = value.path("verificationKey");
                if (!keyValue.isObject() || keyValue.size() != 7
                        || !Set.of("schemaVersion", "keyId", "algorithm", "encodedPublicKey",
                        "createdAt", "state", "provider").equals(fieldNames(keyValue))) {
                    throw new IOException("Mirror evidence fixture key is invalid");
                }
                EvidenceVerificationKey key = new EvidenceVerificationKey(
                        keyValue.path("schemaVersion").asText(), keyValue.path("keyId").asText(),
                        keyValue.path("algorithm").asText(),
                        keyValue.path("encodedPublicKey").asText(),
                        Instant.parse(keyValue.path("createdAt").asText()),
                        keyValue.path("state").asText(), keyValue.path("provider").asText());
                JsonNode bundle = value.path("bundle");
                MirrorEvidenceVerifier.VerificationResult verification =
                        new MirrorEvidenceVerifier().verify(bundle, key);
                if (!verification.verified()) {
                    throw new IOException("Mirror evidence fixture cannot be verified");
                }
                return new MirrorEvidenceCompatibilityFixture(bundle, key);
            } catch (IOException | RuntimeException failure) {
                throw new IllegalStateException(
                        "RG.MIRROR.CLIENT.EVIDENCE_FIXTURE_UNAVAILABLE");
            }
        }

        private static Set<String> fieldNames(JsonNode value) {
            java.util.HashSet<String> names = new java.util.HashSet<>();
            value.fieldNames().forEachRemaining(names::add);
            return Set.copyOf(names);
        }
    }

    private static final class ObservationFixtureHolder {
        private static final CapabilityObservationCompatibilityFixture FIXTURE = load();

        private static CapabilityObservationCompatibilityFixture load() {
            try (InputStream input = CapabilityMirrorProtocol.class.getResourceAsStream(
                    CAPABILITY_OBSERVATION_FIXTURE_RESOURCE)) {
                if (input == null) {
                    throw new IOException("Capability observation fixture is absent");
                }
                JsonNode value = JSON.readTree(input);
                if (!value.isObject() || value.size() != 5
                        || !Set.of("schemaVersion", "verificationKey", "expectedScope",
                        "verificationTime", "observation").equals(fieldNames(value))
                        || !CAPABILITY_OBSERVATION_COMPATIBILITY_V1.equals(
                        value.path("schemaVersion").asText())) {
                    throw new IOException(
                            "Capability observation fixture envelope is invalid");
                }
                CapabilityMirrorSchemaValidator.require(
                        value.path("observation"),
                        CAPABILITY_OBSERVATION_SCHEMA_RESOURCE,
                        "RG.MIRROR.CLIENT.OBSERVATION_FIXTURE_SCHEMA_INVALID");
                CapabilityObservationCompatibilityFixture fixture =
                        CapabilityObservationCompatibilityFixture.from(value);
                CapabilityObservationVerifier.VerificationResult result =
                        new CapabilityObservationVerifier().verify(
                                fixture.observation(),
                                fixture.verificationKey(),
                                fixture.expectedScope(),
                                fixture.verificationTime());
                if (!result.verified()) {
                    throw new IOException(
                            "Capability observation fixture verification failed");
                }
                return fixture;
            } catch (IOException | RuntimeException failure) {
                throw new IllegalStateException(
                        "RG.MIRROR.CLIENT.OBSERVATION_FIXTURE_UNAVAILABLE");
            }
        }

        private static Set<String> fieldNames(JsonNode value) {
            java.util.HashSet<String> names = new java.util.HashSet<>();
            value.fieldNames().forEachRemaining(names::add);
            return Set.copyOf(names);
        }
    }

    private static final class CorpusFixtureHolder {
        private static final CapabilityCorpusCompatibilityFixture FIXTURE = load();

        private static CapabilityCorpusCompatibilityFixture load() {
            try (InputStream input = CapabilityMirrorProtocol.class.getResourceAsStream(
                    CAPABILITY_CORPUS_FIXTURE_RESOURCE)) {
                if (input == null) {
                    throw new IOException(
                            "Capability corpus fixture is absent");
                }
                JsonNode value = JSON.readTree(input);
                if (!value.isObject() || value.size() != 9
                        || !Set.of(
                        "schemaVersion",
                        "verificationTime",
                        "expectedScope",
                        "reviewRequest",
                        "review",
                        "candidateRequest",
                        "revision",
                        "publishRequest",
                        "publication").equals(fieldNames(value))
                        || !CAPABILITY_CORPUS_COMPATIBILITY_V1.equals(
                        value.path("schemaVersion").asText())) {
                    throw new IOException(
                            "Capability corpus fixture envelope is invalid");
                }
                CapabilityCorpusCompatibilityFixture fixture =
                        CapabilityCorpusCompatibilityFixture.from(value);
                CapabilityCorpusVerifier.VerificationResult result =
                        new CapabilityCorpusVerifier().verify(fixture);
                if (!result.verified()) {
                    throw new IOException(
                            "Capability corpus fixture verification failed: "
                                    + result.reasonCode());
                }
                return fixture;
            } catch (IOException | RuntimeException failure) {
                throw new IllegalStateException(
                        "RG.MIRROR.CLIENT.CORPUS_FIXTURE_UNAVAILABLE",
                        failure);
            }
        }

        private static Set<String> fieldNames(JsonNode value) {
            java.util.HashSet<String> names = new java.util.HashSet<>();
            value.fieldNames().forEachRemaining(names::add);
            return Set.copyOf(names);
        }
    }

    private static final class IsolationFixtureHolder {
        private static final MirrorDeploymentIsolationCompatibilityFixture FIXTURE = load();

        private static MirrorDeploymentIsolationCompatibilityFixture load() {
            try (InputStream input = CapabilityMirrorProtocol.class.getResourceAsStream(
                    MIRROR_DEPLOYMENT_ISOLATION_FIXTURE_RESOURCE)) {
                if (input == null) {
                    throw new IOException("Deployment isolation fixture is absent");
                }
                JsonNode value = JSON.readTree(input);
                if (!value.isObject() || value.size() != 5
                        || !Set.of("schemaVersion", "verificationKey", "expectedDeployment",
                        "executionWindow", "attestation").equals(fieldNames(value))
                        || !MIRROR_DEPLOYMENT_ISOLATION_COMPATIBILITY_V1.equals(
                        value.path("schemaVersion").asText())) {
                    throw new IOException("Deployment isolation fixture envelope is invalid");
                }
                MirrorDeploymentIsolationVerificationKey key =
                        MirrorDeploymentIsolationVerificationKey.from(
                                value.path("verificationKey"));
                MirrorDeploymentIdentity expected = MirrorDeploymentIdentity.from(
                                value.path("expectedDeployment"));
                JsonNode executionWindow = value.path("executionWindow");
                if (!executionWindow.isObject() || executionWindow.size() != 2
                        || !Set.of("startedAt", "completedAt")
                        .equals(fieldNames(executionWindow))) {
                    throw new IOException("Deployment isolation execution window is invalid");
                }
                Instant startedAt = Instant.parse(
                        executionWindow.path("startedAt").asText());
                Instant completedAt = Instant.parse(
                        executionWindow.path("completedAt").asText());
                JsonNode attestation = value.path("attestation");
                var verification = new MirrorDeploymentIsolationAttestationVerifier().verify(
                        attestation, key, expected, startedAt, completedAt);
                if (!verification.verified()) {
                    throw new IOException("Deployment isolation fixture cannot be verified: "
                            + verification.reasonCode());
                }
                return new MirrorDeploymentIsolationCompatibilityFixture(attestation, key,
                        expected, startedAt, completedAt);
            } catch (IOException | RuntimeException failure) {
                throw new IllegalStateException(
                        "RG.MIRROR.CLIENT.DEPLOYMENT_ISOLATION_FIXTURE_UNAVAILABLE", failure);
            }
        }

        private static Set<String> fieldNames(JsonNode value) {
            java.util.HashSet<String> names = new java.util.HashSet<>();
            value.fieldNames().forEachRemaining(names::add);
            return Set.copyOf(names);
        }
    }

    private static final class IsolationAuthorityFixtureHolder {
        private static final MirrorDeploymentIsolationAuthorityKeySetCompatibilityFixture FIXTURE =
                load();

        private static MirrorDeploymentIsolationAuthorityKeySetCompatibilityFixture load() {
            try (InputStream input = CapabilityMirrorProtocol.class.getResourceAsStream(
                    MIRROR_DEPLOYMENT_ISOLATION_AUTHORITY_KEY_SET_FIXTURE_RESOURCE)) {
                if (input == null) {
                    throw new IOException("Deployment isolation authority fixture is absent");
                }
                JsonNode value = JSON.readTree(input);
                if (!value.isObject() || value.size() != 5
                        || !Set.of("schemaVersion", "verificationTime", "expectedBinding",
                        "bootstrapRoots", "publication").equals(fieldNames(value))
                        || !MIRROR_DEPLOYMENT_ISOLATION_AUTHORITY_KEY_SET_COMPATIBILITY_V1.equals(
                        value.path("schemaVersion").asText())) {
                    throw new IOException(
                            "Deployment isolation authority fixture envelope is invalid");
                }
                MirrorDeploymentIsolationAuthorityKeySetBinding binding =
                        MirrorDeploymentIsolationAuthorityKeySetBinding.from(
                                value.path("expectedBinding"));
                java.util.ArrayList<MirrorDeploymentIsolationRootVerificationKey> roots =
                        new java.util.ArrayList<>();
                value.path("bootstrapRoots").forEach(root -> roots.add(
                        MirrorDeploymentIsolationRootVerificationKey.from(root)));
                Instant verificationTime = Instant.parse(
                        value.path("verificationTime").asText());
                JsonNode publication = value.path("publication");
                var verification = new MirrorDeploymentIsolationAuthorityKeySetVerifier().verify(
                        publication, binding, roots, null, verificationTime);
                if (!verification.verified()) {
                    throw new IOException(
                            "Deployment isolation authority fixture cannot be verified: "
                                    + verification.reasonCode());
                }
                return new MirrorDeploymentIsolationAuthorityKeySetCompatibilityFixture(
                        publication, binding, roots, verificationTime);
            } catch (IOException | RuntimeException failure) {
                throw new IllegalStateException(
                        "RG.MIRROR.CLIENT.DEPLOYMENT_ISOLATION_AUTHORITY_FIXTURE_UNAVAILABLE",
                        failure);
            }
        }

        private static Set<String> fieldNames(JsonNode value) {
            java.util.HashSet<String> names = new java.util.HashSet<>();
            value.fieldNames().forEachRemaining(names::add);
            return Set.copyOf(names);
        }
    }

    private static final class ClusterFixtureHolder {
        private static final CapabilityCorpusClusterCompatibilityFixture
                FIXTURE = load();

        private static CapabilityCorpusClusterCompatibilityFixture load() {
            try (InputStream input =
                         CapabilityMirrorProtocol.class.getResourceAsStream(
                                 CAPABILITY_CORPUS_CLUSTER_FIXTURE_RESOURCE)) {
                if (input == null) {
                    throw new IOException(
                            "Capability corpus cluster fixture is absent");
                }
                JsonNode value = JSON.readTree(input);
                if (!value.isObject() || value.size() != 8
                        || !Set.of(
                        "schemaVersion",
                        "verificationTime",
                        "expectedScope",
                        "corpusRevision",
                        "corpusPublication",
                        "validation",
                        "publishRequest",
                        "publication").equals(fieldNames(value))
                        || !CAPABILITY_CORPUS_CLUSTER_COMPATIBILITY_V1.equals(
                        value.path("schemaVersion").asText())) {
                    throw new IOException(
                            "Capability corpus cluster fixture envelope is invalid");
                }
                CapabilityCorpusClusterCompatibilityFixture fixture =
                        CapabilityCorpusClusterCompatibilityFixture.from(value);
                CapabilityCorpusClusterVerifier.VerificationResult result =
                        new CapabilityCorpusClusterVerifier().verify(fixture);
                if (!result.verified()) {
                    throw new IOException(
                            "Capability corpus cluster fixture verification failed: "
                                    + result.reasonCode());
                }
                return fixture;
            } catch (IOException | RuntimeException failure) {
                throw new IllegalStateException(
                        "RG.MIRROR.CLIENT.CORPUS_CLUSTER_FIXTURE_UNAVAILABLE",
                        failure);
            }
        }

        private static Set<String> fieldNames(JsonNode value) {
            java.util.HashSet<String> names = new java.util.HashSet<>();
            value.fieldNames().forEachRemaining(names::add);
            return Set.copyOf(names);
        }
    }

    private static final class ClusterBindingFixtureHolder {
        private static final JsonNode FIXTURE = load();

        private static JsonNode load() {
            try (InputStream input =
                         CapabilityMirrorProtocol.class.getResourceAsStream(
                                 FIXTURE_MIRROR_CLUSTER_BINDINGS_FIXTURE_RESOURCE)) {
                if (input == null) {
                    throw new IOException(
                            "Fixture mirror cluster bindings are absent");
                }
                JsonNode value = JSON.readTree(input);
                CapabilityMirrorSchemaValidator.require(
                        value,
                        FIXTURE_MIRROR_CLUSTER_BINDINGS_SCHEMA_RESOURCE,
                        "RG.MIRROR.CLIENT.CLUSTER_BINDINGS_FIXTURE_INVALID");
                return value;
            } catch (IOException | RuntimeException failure) {
                throw new IllegalStateException(
                        "RG.MIRROR.CLIENT.CLUSTER_BINDINGS_FIXTURE_UNAVAILABLE",
                        failure);
            }
        }
    }
}
