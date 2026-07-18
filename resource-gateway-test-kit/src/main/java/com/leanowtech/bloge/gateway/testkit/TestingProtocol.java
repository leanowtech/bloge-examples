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
    /** Payload-free bounded suite-stability evidence wire version. */
    public static final String TEST_SUITE_STABILITY_EVIDENCE_V1 =
            "bloge.testSuiteStabilityEvidence.v1";
    /** Domain-separated detached suite-stability attestation wire version. */
    public static final String TEST_SUITE_STABILITY_ATTESTATION_V1 =
            "bloge.testSuiteStabilityAttestation.v1";
    /** Signed terminal suite-stability execution response wire version. */
    public static final String TEST_SUITE_STABILITY_EXECUTION_RESPONSE_V1 =
            "bloge.testSuiteStabilityExecutionResponse.v1";
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
