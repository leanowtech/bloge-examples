package com.leanowtech.bloge.gateway.testkit;

/** Public wire-version constants shared by the test-kit builders and response guards. */
public final class TestingProtocol {

    /** Single execution request wire version. */
    public static final String TEST_EXECUTION_REQUEST_V1 = "bloge.testExecutionRequest.v1";
    /** Single execution response wire version. */
    public static final String TEST_EXECUTION_RESPONSE_V1 = "bloge.testExecutionResponse.v1";
    /** Current signed single execution response wire version. */
    public static final String TEST_EXECUTION_RESPONSE_V2 = "bloge.testExecutionResponse.v2";
    /** Batch execution request wire version. */
    public static final String TEST_EXECUTION_BATCH_REQUEST_V1 = "bloge.testExecutionBatchRequest.v1";
    /** Batch execution response wire version. */
    public static final String TEST_EXECUTION_BATCH_RESPONSE_V1 = "bloge.testExecutionBatchResponse.v1";
    /** Fixture bundle wire version. */
    public static final String FIXTURE_BUNDLE_V1 = "bloge.fixtureBundle.v1";
    /** Nested deterministic identity and feature-flag fixture wire version. */
    public static final String FIXTURE_EXECUTION_SERVICES_V1 =
            "bloge.fixtureExecutionServices.v1";
    /** Nested fixture wire version adding opaque external test-secret references. */
    public static final String FIXTURE_EXECUTION_SERVICES_V2 =
            "bloge.fixtureExecutionServices.v2";
    /** Credential-free challenge-bound test-secret authority request wire version. */
    public static final String TEST_SECRET_AUTHORITY_REQUEST_V1 =
            "bloge.testSecretAuthorityRequest.v1";
    /** Secret-bearing signed test-secret authority response wire version. */
    public static final String TEST_SECRET_AUTHORITY_RESPONSE_V1 =
            "bloge.testSecretAuthorityResponse.v1";
    /** Payload-free dynamic test-secret authority trust refresh snapshot version. */
    public static final String TEST_SECRET_AUTHORITY_TRUST_REFRESH_SNAPSHOT_V1 =
            "bloge.testSecretAuthorityTrustRefreshSnapshot.v1";
    /** Aggregate database-authoritative test-secret trust cohort snapshot version. */
    public static final String TEST_SECRET_AUTHORITY_TRUST_COHORT_SNAPSHOT_V1 =
            "bloge.testSecretAuthorityTrustCohortSnapshot.v1";
    /** Payload-free test-secret trust cohort gate descriptor version. */
    public static final String TEST_SECRET_AUTHORITY_TRUST_COHORT_DESCRIPTOR_V1 =
            "bloge.testSecretAuthorityTrustCohortDescriptor.v1";
    /** Current aggregate cohort snapshot with signed-inventory generation convergence. */
    public static final String TEST_SECRET_AUTHORITY_TRUST_COHORT_SNAPSHOT_V2 =
            "bloge.testSecretAuthorityTrustCohortSnapshot.v2";
    /** Current cohort descriptor exposing deployment-attested inventory mode. */
    public static final String TEST_SECRET_AUTHORITY_TRUST_COHORT_DESCRIPTOR_V2 =
            "bloge.testSecretAuthorityTrustCohortDescriptor.v2";
    /** Deployment-signed exact test-secret serving-inventory envelope version. */
    public static final String TEST_SECRET_AUTHORITY_SERVING_INVENTORY_V1 =
            "bloge.testSecretAuthorityServingInventory.v1";
    /** Canonical deployment-owned test-secret inventory material version. */
    public static final String TEST_SECRET_AUTHORITY_SERVING_INVENTORY_MATERIAL_V1 =
            "bloge.testSecretAuthorityServingInventoryMaterial.v1";
    /** Active-or-revoked witnessed test-secret inventory publication envelope version. */
    public static final String TEST_SECRET_AUTHORITY_SERVING_INVENTORY_PUBLICATION_V1 =
            "bloge.testSecretAuthorityServingInventoryPublication.v1";
    /** Canonical deployment-signed test-secret inventory publication material version. */
    public static final String TEST_SECRET_AUTHORITY_SERVING_INVENTORY_PUBLICATION_MATERIAL_V1 =
            "bloge.testSecretAuthorityServingInventoryPublicationMaterial.v1";
    /** Independent test-secret inventory witness checkpoint envelope version. */
    public static final String TEST_SECRET_AUTHORITY_SERVING_INVENTORY_WITNESS_V1 =
            "bloge.testSecretAuthorityServingInventoryWitness.v1";
    /** Canonical independent test-secret inventory witness material version. */
    public static final String TEST_SECRET_AUTHORITY_SERVING_INVENTORY_WITNESS_MATERIAL_V1 =
            "bloge.testSecretAuthorityServingInventoryWitnessMaterial.v1";
    /** Durable publication and witness floor candidate version. */
    public static final String TEST_SECRET_AUTHORITY_SERVING_INVENTORY_GENERATION_V1 =
            "bloge.testSecretAuthorityServingInventoryPublicationGeneration.v1";
    /** Aggregate payload-free dynamic inventory refresh snapshot version. */
    public static final String TEST_SECRET_AUTHORITY_SERVING_INVENTORY_REFRESH_SNAPSHOT_V1 =
            "bloge.testSecretAuthorityServingInventoryRefreshSnapshot.v1";
    /** Fixture rule wire version. */
    public static final String FIXTURE_RULE_V1 = "bloge.fixtureRule.v1";
    /** Fixture registration request wire version. */
    public static final String FIXTURE_REGISTRATION_REQUEST_V1 =
            "bloge.fixtureBundleRegistrationRequest.v1";
    /** Stored fixture response wire version. */
    public static final String STORED_FIXTURE_BUNDLE_V1 = "bloge.storedFixtureBundle.v1";
    /** Graph target descriptor wire version. */
    public static final String GRAPH_TARGET_DESCRIPTOR_V1 = "bloge.testGraphTargetDescriptor.v1";
    /** Operator execution request wire version. */
    public static final String OPERATOR_EXECUTION_REQUEST_V1 = "bloge.testOperatorExecutionRequest.v1";
    /** Historical operator target descriptor without a formal composability manifest. */
    @Deprecated(forRemoval = false)
    public static final String OPERATOR_TARGET_DESCRIPTOR_V1 = "bloge.testOperatorTargetDescriptor.v1";
    /** Current operator target descriptor with fail-closed composability facts. */
    public static final String OPERATOR_TARGET_DESCRIPTOR_V2 = "bloge.testOperatorTargetDescriptor.v2";
    /** Historical test-run evidence wire version without semantic identity. */
    public static final String TEST_RUN_EVIDENCE_V1 = "bloge.testRunEvidence.v1";
    /** Current test-run evidence wire version with semantic identity. */
    public static final String TEST_RUN_EVIDENCE_V2 = "bloge.testRunEvidence.v2";
    /** Payload-free provider-state checkpoint used by deterministic durable resume. */
    public static final String EXECUTION_SERVICE_STATE_SNAPSHOT_V1 =
            "bloge.executionServiceStateSnapshot.v1";
    /** Non-blocking durable worker acquisition request wire version. */
    public static final String DURABLE_WORKER_ACQUISITION_REQUEST_V1 =
            "bloge.durableTestWorkerAcquisitionRequest.v1";
    /** Payload-free acquired/no-work worker result wire version. */
    public static final String DURABLE_WORKER_ACQUISITION_RESPONSE_V1 =
            "bloge.durableTestWorkerAcquisitionResponse.v1";
    /** One-signal suspended-or-terminal durable recovery request wire version. */
    public static final String DURABLE_RECOVERY_STEP_REQUEST_V1 =
            "bloge.durableTestRecoveryStepRequest.v1";
    /** Payload-free suspended-or-terminal durable recovery result wire version. */
    public static final String DURABLE_RECOVERY_STEP_RESPONSE_V1 =
            "bloge.durableTestRecoveryStepResponse.v1";
    /** Bounded ordered durable recovery-sequence request wire version. */
    public static final String DURABLE_RECOVERY_SEQUENCE_REQUEST_V1 =
            "bloge.durableTestRecoverySequenceRequest.v1";
    /** Payload-free bounded durable recovery-sequence result wire version. */
    public static final String DURABLE_RECOVERY_SEQUENCE_RESPONSE_V1 =
            "bloge.durableTestRecoverySequenceResponse.v1";
    /** Per-replica request-index rollout challenge wire version. */
    public static final String WORKER_QUARANTINE_REQUEST_INDEX_REPLICA_PROOF_REQUEST_V1 =
            "bloge.workerQuarantineRequestIndexReplicaProofRequest.v1";
    /** Canonical material signed by one request-index rollout replica proof. */
    public static final String WORKER_QUARANTINE_REQUEST_INDEX_REPLICA_PROOF_MATERIAL_V1 =
            "bloge.workerQuarantineRequestIndexReplicaProofMaterial.v1";
    /** Signed per-replica request-index rollout proof wire version. */
    public static final String WORKER_QUARANTINE_REQUEST_INDEX_REPLICA_PROOF_V1 =
            "bloge.workerQuarantineRequestIndexReplicaProof.v1";
    /** Detached test-evidence integrity manifest wire version. */
    public static final String TEST_EVIDENCE_INTEGRITY_V1 = "bloge.testEvidenceIntegrity.v1";
    /** Immutable test-suite wire version. */
    public static final String TEST_SUITE_V1 = "bloge.testSuite.v1";
    /** Immutable suite generation with typed orchestration-semantic requirements. */
    public static final String TEST_SUITE_V2 = "bloge.testSuite.v2";
    /** Immutable admission-only suite generation bound to one boundary plan. */
    public static final String TEST_SUITE_V3 = "bloge.testSuite.v3";
    /** Immutable bounded-property suite generation bound to one seeded plan. */
    public static final String TEST_SUITE_V4 = "bloge.testSuite.v4";
    /** Immutable pure-DSL mutation suite generation bound to one exact oracle and plan. */
    public static final String TEST_SUITE_V5 = "bloge.testSuite.v5";
    /** Seeded validator-proven property case plan wire version. */
    public static final String TEST_PROPERTY_CASE_PLAN_V1 = "bloge.testPropertyCasePlan.v1";
    /** Bounded independently compiling pure-DSL mutation authoring plan wire version. */
    public static final String TEST_MUTATION_CASE_PLAN_V1 = "bloge.testMutationCasePlan.v1";
    /** Exact property-suite materialization request wire version. */
    public static final String TEST_PROPERTY_SUITE_MATERIALIZATION_REQUEST_V1 =
            "bloge.testPropertySuiteMaterializationRequest.v1";
    /** Exact property-suite materialization response wire version. */
    public static final String TEST_PROPERTY_SUITE_MATERIALIZATION_V1 =
            "bloge.testPropertySuiteMaterialization.v1";
    /** Exact pure-DSL mutation-suite materialization request wire version. */
    public static final String TEST_MUTATION_SUITE_MATERIALIZATION_REQUEST_V1 =
            "bloge.testMutationSuiteMaterializationRequest.v1";
    /** Exact pure-DSL mutation-suite materialization response wire version. */
    public static final String TEST_MUTATION_SUITE_MATERIALIZATION_V1 =
            "bloge.testMutationSuiteMaterialization.v1";
    /** Test-suite registration request wire version. */
    public static final String TEST_SUITE_REGISTRATION_REQUEST_V1 =
            "bloge.testSuiteRegistrationRequest.v1";
    /** Stored immutable test-suite response wire version. */
    public static final String STORED_TEST_SUITE_V1 = "bloge.storedTestSuite.v1";
    /** Exact immutable suite execution request wire version. */
    public static final String TEST_SUITE_EXECUTION_REQUEST_V1 =
            "bloge.testSuiteExecutionRequest.v1";
    /** Exact immutable mutation-suite execution request wire version. */
    public static final String TEST_MUTATION_SUITE_EXECUTION_REQUEST_V1 =
            "bloge.testMutationSuiteExecutionRequest.v1";
    /** Bounded idempotent suite-stability rerun request wire version. */
    public static final String TEST_SUITE_STABILITY_EXECUTION_REQUEST_V1 =
            "bloge.testSuiteStabilityExecutionRequest.v1";
    /** Statistical fixed-horizon suite-stability request wire version. */
    public static final String TEST_SUITE_STABILITY_EXECUTION_REQUEST_V2 =
            "bloge.testSuiteStabilityExecutionRequest.v2";
    /** Baseline-conditional exact-rate suite-stability request wire version. */
    public static final String TEST_SUITE_STABILITY_EXECUTION_REQUEST_V3 =
            "bloge.testSuiteStabilityExecutionRequest.v3";
    /** Anytime-valid sequential suite-stability request wire version. */
    public static final String TEST_SUITE_STABILITY_EXECUTION_REQUEST_V4 =
            "bloge.testSuiteStabilityExecutionRequest.v4";
    /** Payload-free bounded suite-stability evidence wire version. */
    public static final String TEST_SUITE_STABILITY_EVIDENCE_V1 =
            "bloge.testSuiteStabilityEvidence.v1";
    /** Source-promotion-closed suite-stability evidence wire version. */
    public static final String TEST_SUITE_STABILITY_EVIDENCE_V2 =
            "bloge.testSuiteStabilityEvidence.v2";
    /** Exact-binomial statistical suite-stability evidence wire version. */
    public static final String TEST_SUITE_STABILITY_EVIDENCE_V3 =
            "bloge.testSuiteStabilityEvidence.v3";
    /** Baseline-conditional exact-rate suite-stability evidence wire version. */
    public static final String TEST_SUITE_STABILITY_EVIDENCE_V4 =
            "bloge.testSuiteStabilityEvidence.v4";
    /** Anytime-valid first-terminal-prefix suite-stability evidence wire version. */
    public static final String TEST_SUITE_STABILITY_EVIDENCE_V5 =
            "bloge.testSuiteStabilityEvidence.v5";
    /** Domain-separated detached suite-stability attestation wire version. */
    public static final String TEST_SUITE_STABILITY_ATTESTATION_V1 =
            "bloge.testSuiteStabilityAttestation.v1";
    /** Source-promotion-closed suite-stability attestation wire version. */
    public static final String TEST_SUITE_STABILITY_ATTESTATION_V2 =
            "bloge.testSuiteStabilityAttestation.v2";
    /** Exact-binomial statistical suite-stability attestation wire version. */
    public static final String TEST_SUITE_STABILITY_ATTESTATION_V3 =
            "bloge.testSuiteStabilityAttestation.v3";
    /** Baseline-conditional exact-rate suite-stability attestation wire version. */
    public static final String TEST_SUITE_STABILITY_ATTESTATION_V4 =
            "bloge.testSuiteStabilityAttestation.v4";
    /** Anytime-valid sequential suite-stability attestation wire version. */
    public static final String TEST_SUITE_STABILITY_ATTESTATION_V5 =
            "bloge.testSuiteStabilityAttestation.v5";
    /** Signed terminal suite-stability execution response wire version. */
    public static final String TEST_SUITE_STABILITY_EXECUTION_RESPONSE_V1 =
            "bloge.testSuiteStabilityExecutionResponse.v1";
    /** Signed terminal suite-stability response with source-promotion closure. */
    public static final String TEST_SUITE_STABILITY_EXECUTION_RESPONSE_V2 =
            "bloge.testSuiteStabilityExecutionResponse.v2";
    /** Signed terminal statistical suite-stability response wire version. */
    public static final String TEST_SUITE_STABILITY_EXECUTION_RESPONSE_V3 =
            "bloge.testSuiteStabilityExecutionResponse.v3";
    /** Signed terminal baseline-conditional exact-rate stability response wire version. */
    public static final String TEST_SUITE_STABILITY_EXECUTION_RESPONSE_V4 =
            "bloge.testSuiteStabilityExecutionResponse.v4";
    /** Signed terminal anytime-valid sequential stability response wire version. */
    public static final String TEST_SUITE_STABILITY_EXECUTION_RESPONSE_V5 =
            "bloge.testSuiteStabilityExecutionResponse.v5";
    /** Historical payload-free progress requiring a complete planned horizon. */
    public static final String TEST_SUITE_STABILITY_PROGRESS_V1 =
            "bloge.testSuiteStabilityProgress.v1";
    /** Payload-free progress exposing a fixed or sequential terminal reason. */
    public static final String TEST_SUITE_STABILITY_PROGRESS_V2 =
            "bloge.testSuiteStabilityProgress.v2";
    /** Exact retained-window suite-stability trend request wire version. */
    public static final String TEST_SUITE_STABILITY_TREND_ANALYSIS_REQUEST_V1 =
            "bloge.testSuiteStabilityTrendAnalysisRequest.v1";
    /** Payload-free derived retained-window trend evidence wire version. */
    public static final String TEST_SUITE_STABILITY_TREND_EVIDENCE_V1 =
            "bloge.testSuiteStabilityTrendEvidence.v1";
    /** Domain-separated retained-window trend attestation wire version. */
    public static final String TEST_SUITE_STABILITY_TREND_ATTESTATION_V1 =
            "bloge.testSuiteStabilityTrendAttestation.v1";
    /** Signed retained-window suite-stability trend response wire version. */
    public static final String TEST_SUITE_STABILITY_TREND_ANALYSIS_RESPONSE_V1 =
            "bloge.testSuiteStabilityTrendAnalysisResponse.v1";
    /** Exact compact-observation range trend request wire version. */
    public static final String TEST_SUITE_STABILITY_CROSS_RETENTION_TREND_REQUEST_V1 =
            "bloge.testSuiteStabilityCrossRetentionTrendAnalysisRequest.v1";
    /** Durable payload-free compact observation evidence wire version. */
    public static final String TEST_SUITE_STABILITY_OBSERVATION_EVIDENCE_V1 =
            "bloge.testSuiteStabilityObservationEvidence.v1";
    /** Domain-separated compact observation attestation wire version. */
    public static final String TEST_SUITE_STABILITY_OBSERVATION_ATTESTATION_V1 =
            "bloge.testSuiteStabilityObservationAttestation.v1";
    /** Producer compact-observation ledger entry wire version. */
    public static final String TEST_SUITE_STABILITY_OBSERVATION_ENTRY_V1 =
            "bloge.testSuiteStabilityObservationLedgerEntry.v1";
    /** Producer compact-observation ledger head wire version. */
    public static final String TEST_SUITE_STABILITY_OBSERVATION_HEAD_V1 =
            "bloge.testSuiteStabilityObservationLedgerHead.v1";
    /** Producer observation-ledger floor wire version. */
    public static final String TEST_SUITE_STABILITY_OBSERVATION_FLOOR_V1 =
            "bloge.testSuiteStabilityObservationLedgerFloor.v1";
    /** Producer bounded local observation archive segment wire version. */
    public static final String TEST_SUITE_STABILITY_OBSERVATION_ARCHIVE_V1 =
            "bloge.testSuiteStabilityObservationArchiveSegment.v1";
    /** Producer signed floor-retirement evidence wire version. */
    public static final String TEST_SUITE_STABILITY_OBSERVATION_RETIREMENT_EVIDENCE_V1 =
            "bloge.testSuiteStabilityObservationFloorRetirementEvidence.v1";
    /** Domain-separated floor-retirement signature wire version. */
    public static final String TEST_SUITE_STABILITY_OBSERVATION_RETIREMENT_ATTESTATION_V1 =
            "bloge.testSuiteStabilityObservationFloorRetirementAttestation.v1";
    /** Exact observation-ledger lifecycle page request wire version. */
    public static final String TEST_SUITE_STABILITY_OBSERVATION_LIFECYCLE_REQUEST_V1 =
            "bloge.testSuiteStabilityObservationLedgerLifecyclePageRequest.v1";
    /** Producer observation-ledger lifecycle page wire version. */
    public static final String TEST_SUITE_STABILITY_OBSERVATION_LIFECYCLE_PAGE_V1 =
            "bloge.testSuiteStabilityObservationLedgerLifecyclePage.v1";
    /** Domain-separated observation-ledger lifecycle page signature wire version. */
    public static final String TEST_SUITE_STABILITY_OBSERVATION_LIFECYCLE_ATTESTATION_V1 =
            "bloge.testSuiteStabilityObservationLedgerLifecycleAttestation.v1";
    /** Signed observation-ledger lifecycle page response wire version. */
    public static final String TEST_SUITE_STABILITY_OBSERVATION_LIFECYCLE_RESPONSE_V1 =
            "bloge.testSuiteStabilityObservationLedgerLifecyclePageResponse.v1";
    /** Producer challenge-bound external archive request wire version. */
    public static final String TEST_SUITE_STABILITY_OBSERVATION_EXTERNAL_ARCHIVE_REQUEST_V1 =
            "bloge.testSuiteStabilityObservationExternalArchiveRequest.v1";
    /** External authority immutable-archive receipt wire version. */
    public static final String TEST_SUITE_STABILITY_OBSERVATION_EXTERNAL_ARCHIVE_RECEIPT_V1 =
            "bloge.testSuiteStabilityObservationExternalArchiveReceipt.v1";
    /** Canonical multi-copy external archive receipt-set wire version. */
    public static final String TEST_SUITE_STABILITY_OBSERVATION_EXTERNAL_ARCHIVE_RECEIPT_SET_V1 =
            "bloge.testSuiteStabilityObservationExternalArchiveReceiptSet.v1";
    /** Producer receipt-aware observation-ledger lifecycle page wire version. */
    public static final String TEST_SUITE_STABILITY_OBSERVATION_LIFECYCLE_PAGE_V2 =
            "bloge.testSuiteStabilityObservationLedgerLifecyclePage.v2";
    /** Domain-separated receipt-aware lifecycle page signature wire version. */
    public static final String TEST_SUITE_STABILITY_OBSERVATION_LIFECYCLE_ATTESTATION_V2 =
            "bloge.testSuiteStabilityObservationLedgerLifecycleAttestation.v2";
    /** Signed receipt-aware observation-ledger lifecycle response wire version. */
    public static final String TEST_SUITE_STABILITY_OBSERVATION_LIFECYCLE_RESPONSE_V2 =
            "bloge.testSuiteStabilityObservationLedgerLifecyclePageResponse.v2";
    /** Producer floor/head/cursor-pinned observation range wire version. */
    public static final String TEST_SUITE_STABILITY_OBSERVATION_RANGE_V1 =
            "bloge.testSuiteStabilityObservationLedgerRange.v1";
    /** Signed cross-retention trend evidence wire version. */
    public static final String TEST_SUITE_STABILITY_CROSS_RETENTION_TREND_EVIDENCE_V1 =
            "bloge.testSuiteStabilityCrossRetentionTrendEvidence.v1";
    /** Domain-separated cross-retention range attestation wire version. */
    public static final String TEST_SUITE_STABILITY_CROSS_RETENTION_TREND_ATTESTATION_V1 =
            "bloge.testSuiteStabilityCrossRetentionTrendAttestation.v1";
    /** Signed compact-observation range trend response wire version. */
    public static final String TEST_SUITE_STABILITY_CROSS_RETENTION_TREND_RESPONSE_V1 =
            "bloge.testSuiteStabilityCrossRetentionTrendAnalysisResponse.v1";
    /** Asynchronous suite-stability job submission request wire version. */
    public static final String TEST_SUITE_STABILITY_JOB_SUBMIT_REQUEST_V1 =
            "bloge.testSuiteStabilityJobSubmitRequest.v1";
    /** Idempotent asynchronous suite-stability job cancellation request wire version. */
    public static final String TEST_SUITE_STABILITY_JOB_CANCEL_REQUEST_V1 =
            "bloge.testSuiteStabilityJobCancelRequest.v1";
    /** Payload-free asynchronous suite-stability job lifecycle wire version. */
    public static final String TEST_SUITE_STABILITY_JOB_VIEW_V1 =
            "bloge.testSuiteStabilityJobView.v1";
    /** Asynchronous suite-stability queue admission response wire version. */
    public static final String TEST_SUITE_STABILITY_JOB_SUBMIT_RESPONSE_V1 =
            "bloge.testSuiteStabilityJobSubmitResponse.v1";
    /** Immutable suite execution response wire version. */
    public static final String TEST_SUITE_EXECUTION_RESPONSE_V1 =
            "bloge.testSuiteExecutionResponse.v1";
    /** Current signed immutable suite execution response wire version. */
    public static final String TEST_SUITE_EXECUTION_RESPONSE_V2 =
            "bloge.testSuiteExecutionResponse.v2";
    /** Signed suite response carrying semantic aggregate evidence. */
    public static final String TEST_SUITE_EXECUTION_RESPONSE_V3 =
            "bloge.testSuiteExecutionResponse.v3";
    /** Signed suite response carrying schema-admission evidence without business execution. */
    public static final String TEST_SUITE_EXECUTION_RESPONSE_V4 =
            "bloge.testSuiteExecutionResponse.v4";
    /** Signed suite response carrying bounded-property execution evidence. */
    public static final String TEST_SUITE_EXECUTION_RESPONSE_V5 =
            "bloge.testSuiteExecutionResponse.v5";
    /** Signed suite response carrying pure-DSL mutation score evidence. */
    public static final String TEST_SUITE_EXECUTION_RESPONSE_V6 =
            "bloge.testSuiteExecutionResponse.v6";
    /** Aggregate immutable suite-run evidence wire version. */
    public static final String TEST_SUITE_RUN_EVIDENCE_V1 = "bloge.testSuiteRunEvidence.v1";
    /** Aggregate suite evidence with a typed semantic coverage verdict. */
    public static final String TEST_SUITE_RUN_EVIDENCE_V2 = "bloge.testSuiteRunEvidence.v2";
    /** Aggregate schema-admission evidence with exact plan and validator provenance. */
    public static final String TEST_SUITE_RUN_EVIDENCE_V3 = "bloge.testSuiteRunEvidence.v3";
    /** Aggregate bounded-property evidence with root/shrink lineage and honest minimality. */
    public static final String TEST_SUITE_RUN_EVIDENCE_V4 = "bloge.testSuiteRunEvidence.v4";
    /** Aggregate pure-DSL mutation evidence with baseline and mutant child closure. */
    public static final String TEST_SUITE_RUN_EVIDENCE_V5 = "bloge.testSuiteRunEvidence.v5";
    /** Signed suite checkpoint and terminal closure wire version. */
    public static final String TEST_SUITE_RUN_ATTESTATION_V1 =
            "bloge.testSuiteRunAttestation.v1";
    /** Domain-separated attestation for semantic aggregate evidence. */
    public static final String TEST_SUITE_RUN_ATTESTATION_V2 =
            "bloge.testSuiteRunAttestation.v2";
    /** Domain-separated attestation with an intentionally empty business-child closure. */
    public static final String TEST_SUITE_RUN_ATTESTATION_V3 =
            "bloge.testSuiteRunAttestation.v3";
    /** Domain-separated attestation for bounded-property aggregate and child closure. */
    public static final String TEST_SUITE_RUN_ATTESTATION_V4 =
            "bloge.testSuiteRunAttestation.v4";
    /** Domain-separated attestation for baseline and prefixed mutant child closure. */
    public static final String TEST_SUITE_RUN_ATTESTATION_V5 =
            "bloge.testSuiteRunAttestation.v5";
    /** Portable payload-free terminal suite evidence bundle wire version. */
    public static final String TEST_SUITE_EVIDENCE_BUNDLE_V1 =
            "bloge.testSuiteEvidenceBundle.v1";
    /** Portable payload-free bundle carrying semantic aggregate evidence. */
    public static final String TEST_SUITE_EVIDENCE_BUNDLE_V2 =
            "bloge.testSuiteEvidenceBundle.v2";
    /** Portable payload-free bundle carrying schema-admission evidence. */
    public static final String TEST_SUITE_EVIDENCE_BUNDLE_V3 =
            "bloge.testSuiteEvidenceBundle.v3";
    /** Portable payload-free bundle carrying bounded-property evidence. */
    public static final String TEST_SUITE_EVIDENCE_BUNDLE_V4 =
            "bloge.testSuiteEvidenceBundle.v4";
    /** Portable payload-free bundle carrying pure-DSL mutation evidence. */
    public static final String TEST_SUITE_EVIDENCE_BUNDLE_V5 =
            "bloge.testSuiteEvidenceBundle.v5";
    /** Resource Gateway evidence verification key wire version. */
    public static final String EVIDENCE_VERIFICATION_KEY_V1 =
            "toolStudio.resourceGateway.evidenceVerificationKey.v1";
    /** Signed, externally pinnable multi-key lifecycle snapshot wire version. */
    public static final String EVIDENCE_VERIFICATION_KEY_SET_V1 =
            "toolStudio.resourceGateway.evidenceVerificationKeySet.v1";
    /** Externally authorized append-only key-set pin publication. */
    public static final String EVIDENCE_KEY_SET_TRUST_PUBLICATION_V1 =
            "toolStudio.resourceGateway.evidenceKeySetTrustPublication.v1";
    /** Bounded transparency proof page joined to the current evidence key set. */
    public static final String EVIDENCE_KEY_SET_TRUST_BUNDLE_V1 =
            "toolStudio.resourceGateway.evidenceKeySetTrustBundle.v1";
    /** Built-in graph catalog materialization response wire version. */
    public static final String TEST_SUITE_CATALOG_MATERIALIZATION_V1 =
            "bloge.testSuiteCatalogMaterialization.v1";
    /** Payload-free ANEKE workbook projection for semantic suite and evidence v2. */
    public static final String SEMANTIC_CORRECTNESS_WORKBOOK_V1 =
            "toolStudio.resourceGateway.semanticCorrectnessWorkbookBundle.v1";
    /** Governance gate generation with reconstructable semantic workbook decision basis. */
    public static final String GOVERNANCE_GATE_RESULT_V3 =
            "toolStudio.resourceGateway.gateResult.v3";

    /** Classpath location of the authoritative JSON Schema packaged in the test-kit JAR. */
    public static final String SCHEMA_RESOURCE =
            "/schemas/resource-gateway-testing/testing-control-plane-v1.schema.json";
    /** Classpath location of the private signed test-secret authority JSON Schema. */
    public static final String TEST_SECRET_AUTHORITY_SCHEMA_RESOURCE =
            "/schemas/resource-gateway-testing/test-secret-authority-v1.schema.json";
    /** Classpath location of the witnessed test-secret inventory publication JSON Schema. */
    public static final String TEST_SECRET_AUTHORITY_SERVING_INVENTORY_PUBLICATION_SCHEMA_RESOURCE =
            "/schemas/resource-gateway-testing/"
                    + "test-secret-authority-serving-inventory-publication-v1.schema.json";
    /** Classpath location of the semantic correctness workbook JSON Schema. */
    public static final String SEMANTIC_WORKBOOK_SCHEMA_RESOURCE =
            "/schemas/tool-studio-resource-gateway/semantic-correctness-workbook-bundle-v1.schema.json";
    /** Classpath location of the semantic governance gate JSON Schema. */
    public static final String GOVERNANCE_GATE_V3_SCHEMA_RESOURCE =
            "/schemas/tool-studio-resource-gateway/governance-gate-result-v3.schema.json";
    /** Classpath location of the evidence key-set trust bundle JSON Schema. */
    public static final String EVIDENCE_TRUST_BUNDLE_SCHEMA_RESOURCE =
            "/schemas/tool-studio-resource-gateway/evidence-key-set-trust-bundle-v1.schema.json";

    private TestingProtocol() {
    }
}
