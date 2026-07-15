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
    /** Test-run evidence wire version. */
    public static final String TEST_RUN_EVIDENCE_V1 = "bloge.testRunEvidence.v1";
    /** Detached test-evidence integrity manifest wire version. */
    public static final String TEST_EVIDENCE_INTEGRITY_V1 = "bloge.testEvidenceIntegrity.v1";
    /** Immutable test-suite wire version. */
    public static final String TEST_SUITE_V1 = "bloge.testSuite.v1";
    /** Immutable suite generation with typed orchestration-semantic requirements. */
    public static final String TEST_SUITE_V2 = "bloge.testSuite.v2";
    /** Test-suite registration request wire version. */
    public static final String TEST_SUITE_REGISTRATION_REQUEST_V1 =
            "bloge.testSuiteRegistrationRequest.v1";
    /** Stored immutable test-suite response wire version. */
    public static final String STORED_TEST_SUITE_V1 = "bloge.storedTestSuite.v1";
    /** Exact immutable suite execution request wire version. */
    public static final String TEST_SUITE_EXECUTION_REQUEST_V1 =
            "bloge.testSuiteExecutionRequest.v1";
    /** Immutable suite execution response wire version. */
    public static final String TEST_SUITE_EXECUTION_RESPONSE_V1 =
            "bloge.testSuiteExecutionResponse.v1";
    /** Current signed immutable suite execution response wire version. */
    public static final String TEST_SUITE_EXECUTION_RESPONSE_V2 =
            "bloge.testSuiteExecutionResponse.v2";
    /** Signed suite response carrying semantic aggregate evidence. */
    public static final String TEST_SUITE_EXECUTION_RESPONSE_V3 =
            "bloge.testSuiteExecutionResponse.v3";
    /** Aggregate immutable suite-run evidence wire version. */
    public static final String TEST_SUITE_RUN_EVIDENCE_V1 = "bloge.testSuiteRunEvidence.v1";
    /** Aggregate suite evidence with a typed semantic coverage verdict. */
    public static final String TEST_SUITE_RUN_EVIDENCE_V2 = "bloge.testSuiteRunEvidence.v2";
    /** Signed suite checkpoint and terminal closure wire version. */
    public static final String TEST_SUITE_RUN_ATTESTATION_V1 =
            "bloge.testSuiteRunAttestation.v1";
    /** Domain-separated attestation for semantic aggregate evidence. */
    public static final String TEST_SUITE_RUN_ATTESTATION_V2 =
            "bloge.testSuiteRunAttestation.v2";
    /** Portable payload-free terminal suite evidence bundle wire version. */
    public static final String TEST_SUITE_EVIDENCE_BUNDLE_V1 =
            "bloge.testSuiteEvidenceBundle.v1";
    /** Portable payload-free bundle carrying semantic aggregate evidence. */
    public static final String TEST_SUITE_EVIDENCE_BUNDLE_V2 =
            "bloge.testSuiteEvidenceBundle.v2";
    /** Resource Gateway evidence verification key wire version. */
    public static final String EVIDENCE_VERIFICATION_KEY_V1 =
            "toolStudio.resourceGateway.evidenceVerificationKey.v1";
    /** Signed, externally pinnable multi-key lifecycle snapshot wire version. */
    public static final String EVIDENCE_VERIFICATION_KEY_SET_V1 =
            "toolStudio.resourceGateway.evidenceVerificationKeySet.v1";
    /** Built-in graph catalog materialization response wire version. */
    public static final String TEST_SUITE_CATALOG_MATERIALIZATION_V1 =
            "bloge.testSuiteCatalogMaterialization.v1";
    /** Payload-free ANEKE workbook projection for semantic suite and evidence v2. */
    public static final String SEMANTIC_CORRECTNESS_WORKBOOK_V1 =
            "toolStudio.resourceGateway.semanticCorrectnessWorkbookBundle.v1";

    /** Classpath location of the authoritative JSON Schema packaged in the test-kit JAR. */
    public static final String SCHEMA_RESOURCE =
            "/schemas/resource-gateway-testing/testing-control-plane-v1.schema.json";
    /** Classpath location of the semantic correctness workbook JSON Schema. */
    public static final String SEMANTIC_WORKBOOK_SCHEMA_RESOURCE =
            "/schemas/tool-studio-resource-gateway/semantic-correctness-workbook-bundle-v1.schema.json";

    private TestingProtocol() {
    }
}
